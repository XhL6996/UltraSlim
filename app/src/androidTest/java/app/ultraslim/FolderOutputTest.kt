package app.ultraslim

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.convert.FolderOutput
import app.ultraslim.convert.OutputStore
import app.ultraslim.convert.MediaStoreSaver
import app.ultraslim.convert.OutputSaveError
import app.ultraslim.convert.OutputFileTime
import app.ultraslim.convert.SourceMedia
import app.ultraslim.convert.SourceTimestamps
import app.ultraslim.convert.OutputMetadataStore
import app.ultraslim.data.BatchEntity
import app.ultraslim.data.ConversionDefaults
import app.ultraslim.data.HistoryEntity
import app.ultraslim.data.decodeDefaults
import app.ultraslim.data.encode
import app.ultraslim.util.outputName
import app.ultraslim.ui.home.HomeViewModel
import androidx.lifecycle.ViewModelStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class FolderOutputTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UltraSlimApp
    private val tree = DocumentsContract.buildTreeDocumentUri("app.ultraslim.tests.documents", "output")
    private val root get() = DocumentsContract.buildDocumentUriUsingTree(tree, "output")

    private fun grant(revoke: Boolean = false) {
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${instrumentation.context.packageName}/app.ultraslim.FolderGrantActivity " +
                "--es target ${app.packageName} --es treeId output --ez write true --ez revoke $revoke"
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }

    @After fun releaseGrant() { grant(revoke = true) }

    @Test fun staleDocumentMetadataDoesNotRejectAFileWhoseActualMtimeIsCorrect() {
        grant()
        val uri = requireNotNull(DocumentsContract.createDocument(app.contentResolver, root,
            "image/jpeg", "mtime_${UUID.randomUUID()}.jpg"))
        try {
            assertEquals(1L, SourceMedia.modifiedTime(app, uri))
            // Provider reports stale mtime, but the granted descriptor has the correct timestamp.
            OutputFileTime.require(app, uri, 1_600_000_000_123L)
            app.contentResolver.openFileDescriptor(uri, "r")!!.use {
                assertEquals(1_600_000_000L, android.system.Os.fstat(it.fileDescriptor).st_mtime)
            }
        } finally { DocumentsContract.deleteDocument(app.contentResolver, uri) }
    }

    private suspend fun record(task: String, name: String = outputName("photo.jpg", "jpg", task, 0),
        keepFilename: Boolean = false,
        timestamps: SourceTimestamps = SourceTimestamps()): HistoryEntity {
        app.database.taskDao().putBatch(BatchEntity(task, "[]",
            ConversionDefaults(outputDirectoryUri = tree.toString(), outputDirectoryName = "Converted photos",
                keepFilename = keepFilename).encode()))
        val file = File(app.filesDir, "outputs/$task.jpg").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open("minnie-320x240-yuv-icc.jpg").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        timestamps.outputModifiedAt?.let { assertTrue(file.setLastModified(it)) }
        OutputMetadataStore.write(file, timestamps)
        val row = HistoryEntity(displayName = name, inputUri = "content://test/source", outputUri = file.path,
            inputBytes = file.length() * 2, outputBytes = file.length(), width = 320, height = 240,
            gainmapWidth = 0, gainmapHeight = 0, hadGainMap = false, format = "JPEG", inputFormat = "JPEG",
            durationMs = 1.0, maxContentBoost = 1f, createdAt = System.currentTimeMillis(), status = "DONE",
            taskId = task, batchId = task, saveState = "PENDING")
        return row.copy(id = app.database.historyDao().insert(row))
    }

    @Test fun originalNamesNeverAdoptExistingIdenticalPhotosAndInterruptedSaveRecoversItsOwnUri() = runBlocking {
        grant()
        val name = "original_${UUID.randomUUID()}.jpg"
        val first = record(UUID.randomUUID().toString(), name, keepFilename = true)
        val second = record(UUID.randomUUID().toString(), name, keepFilename = true)
        val owned = mutableSetOf<Uri>()
        try {
            val saved = OutputStore.save(app, first)
            assertEquals("SAVED", saved.saveState)
            assertEquals(name, saved.displayName)
            owned += Uri.parse(saved.outputUri)
            // Same bytes and same desired name are still a conflict, not proof of ownership.
            val file = File(second.outputUri)
            val expected = requireNotNull(MediaStoreSaver.fingerprintOf(file))
            val uri = FolderOutput.save(app, file, tree, name, "image/jpeg", expected, keepFilename = true)
            owned += uri
            assertNotEquals(saved.outputUri, uri.toString())
            assertEquals(uri, FolderOutput.save(app, file, tree, name, "image/jpeg", expected, keepFilename = true))
            // Save after a process interruption uses its receipt and keeps the batch's naming option.
            val result = OutputStore.save(app, second)
            assertEquals(uri.toString(), result.outputUri)
            assertEquals(name.substringBeforeLast('.') + "_${expected.sha256.take(8)}.jpg", result.displayName)
            assertFalse(file.exists())
            assertArrayEquals(app.contentResolver.openInputStream(Uri.parse(saved.outputUri))!!.use { it.readBytes() },
                app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally {
            owned.forEach { DocumentsContract.deleteDocument(app.contentResolver, it) }
            OutputStore.delete(app, first.id)
            OutputStore.delete(app, second.id)
        }
    }

    @Test fun filenameOptionRoundTripsAndObsoleteTimestampSwitchIsIgnored() = runBlocking {
        val repo = app.settingsRepository
        val original = repo.defaults.first()
        try {
            val options = ConversionDefaults(keepFilename = true)
            assertEquals(options, decodeDefaults(options.encode()))
            assertFalse(decodeDefaults("{}").keepFilename)
            val legacy = options.copy(avifGainmapCompatibility = false, heicGainmapCompatibility = false)
            assertEquals(legacy, decodeDefaults("{\"keepFilename\":true,\"keepModifiedTime\":false}"))
            assertEquals(legacy, decodeDefaults("{\"keepFilename\":true,\"keepModifiedTime\":true}"))
            assertFalse(options.encode().contains("keepModifiedTime"))
            repo.setDefaults(options)
            assertTrue(repo.defaults.first().keepFilename)
            repo.setKeepFilename(false)
            assertFalse(repo.defaults.first().keepFilename)
        } finally { repo.setDefaults(original) }
    }

    @Test fun unsupportedOutputTimestampKeepsPrivateResultAndCleansOnlyNewDocument() = runBlocking {
        grant()
        val name = "nomtime_${UUID.randomUUID()}.jpg"
        val row = record(UUID.randomUUID().toString(), name, keepFilename = true,
            timestamps = SourceTimestamps(modifiedAt = 1_600_000_000_000L))
        val file = File(row.outputUri)
        try {
            assertTrue(file.setLastModified(1_600_000_000_000L))
            assertEquals("FAILED", OutputStore.save(app, row).saveState)
            val failed = requireNotNull(app.database.historyDao().byId(row.id))
            assertTrue(requireNotNull(OutputSaveError.read(failed)).contains("修改时间"))
            assertTrue(file.isFile)
            assertEquals(1_600_000_000_000L, file.lastModified())
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, "output")
            app.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use {
                val index = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (it.moveToNext()) assertNotEquals(name, it.getString(index))
            }
        } finally { OutputStore.delete(app, row.id) }
    }

    @Test fun captureTimeFallbackRequiresWritableTimestampAndRetainsPrivateResultOnFailure() = runBlocking {
        grant()
        val capture = 1_500_000_000_345L
        val row = record(UUID.randomUUID().toString(), "nomtime_${UUID.randomUUID()}.jpg",
            timestamps = SourceTimestamps(dateTaken = capture))
        try {
            val saved = OutputStore.save(app, row)
            assertEquals("FAILED", saved.saveState)
            assertTrue(requireNotNull(OutputSaveError.read(saved)).contains("修改时间"))
            assertEquals(capture, File(row.outputUri).lastModified())
            assertEquals(row.outputUri, saved.outputUri)
        } finally {
            OutputStore.delete(app, row.id)
        }
    }

    @Test fun unknownSourceDatesDoNotForceThePrivateConversionTimeOntoOutput() = runBlocking {
        grant()
        // This provider refuses mtime writes, but no timestamp was known, so no write is needed.
        val row = record(UUID.randomUUID().toString(), "nomtime_${UUID.randomUUID()}.jpg",
            timestamps = SourceTimestamps())
        var published: Uri? = null
        try {
            val saved = OutputStore.save(app, row)
            assertEquals("SAVED", saved.saveState)
            published = Uri.parse(saved.outputUri)
        } finally {
            published?.let { DocumentsContract.deleteDocument(app.contentResolver, it) }
            OutputStore.delete(app, row.id)
        }
    }

    @Test fun batchDestinationIsStableAndRecoveryDoesNotOverwriteFiles() = runBlocking {
        grant()
        assertEquals("Converted photos", FolderOutput.retain(app, tree))
        val defaults = app.settingsRepository.defaults.first()
        val row = record(UUID.randomUUID().toString())
        val file = File(row.outputUri)
        val bytes = file.readBytes()
        val collision = DocumentsContract.createDocument(app.contentResolver, root, "image/jpeg", row.displayName)!!
        val other = byteArrayOf(1, 2, 3)
        app.contentResolver.openOutputStream(collision)!!.use { it.write(other) }
        var published: Uri? = null
        try {
            // A later preference change must not redirect this batch to the gallery.
            app.settingsRepository.setOutputDirectory("", "")
            val saved = OutputStore.save(app, row)
            assertEquals("SAVED", saved.saveState)
            published = Uri.parse(saved.outputUri)
            assertEquals(tree.authority, published.authority)
            assertNotEquals(collision, published)
            assertFalse(file.exists())
            assertArrayEquals(bytes, app.contentResolver.openInputStream(published)!!.use { it.readBytes() })
            assertArrayEquals(other, app.contentResolver.openInputStream(collision)!!.use { it.readBytes() })
            // Simulate a crash after writing the document but before checkpointing its URI.
            file.writeBytes(bytes)
            app.database.historyDao().update(row)
            assertEquals(saved.outputUri, OutputStore.save(app, row).outputUri)
            assertFalse(file.exists())
            OutputStore.delete(app, row.id)
            assertArrayEquals(bytes, app.contentResolver.openInputStream(published)!!.use { it.readBytes() })
        } finally {
            published?.let { DocumentsContract.deleteDocument(app.contentResolver, it) }
            DocumentsContract.deleteDocument(app.contentResolver, collision)
            OutputStore.delete(app, row.id)
            file.delete()
            app.settingsRepository.setDefaults(defaults)
        }
    }

    @Test fun lostGrantKeepsPrivateResultAndCanRetryAfterReauthorization() = runBlocking {
        grant()
        FolderOutput.retain(app, tree)
        val row = record(UUID.randomUUID().toString())
        var published: Uri? = null
        try {
            grant(revoke = true)
            val failed = OutputStore.save(app, row)
            assertEquals("FAILED", failed.saveState)
            assertEquals(row.outputUri, failed.outputUri)
            assertTrue(File(row.outputUri).exists())
            grant()
            FolderOutput.retain(app, tree)
            val saved = OutputStore.save(app, failed)
            assertEquals("SAVED", saved.saveState)
            published = Uri.parse(saved.outputUri)
            assertEquals(tree.authority, published.authority)
        } finally {
            grant()
            published?.let { DocumentsContract.deleteDocument(app.contentResolver, it) }
            OutputStore.delete(app, row.id)
            File(row.outputUri).delete()
        }
    }

    @Test fun failedDocumentWriteIsCleanedUpAndPrivateResultRemains() = runBlocking {
        grant()
        val name = "failwrite_${UUID.randomUUID()}.jpg"
        val row = record(UUID.randomUUID().toString(), name)
        try {
            assertEquals("FAILED", OutputStore.save(app, row).saveState)
            assertTrue(File(row.outputUri).exists())
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, "output")
            app.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use {
                while (it.moveToNext()) assertNotEquals(name, it.getString(0))
            }
        } finally {
            OutputStore.delete(app, row.id)
            File(row.outputUri).delete()
        }
    }

    @Test fun replacementTemporarilyOverridesFolderAndRestoresItWhenDisabled() = runBlocking {
        val repo = app.settingsRepository
        val original = repo.defaults.first()
        try {
            repo.setAutoReplaceOriginals(true)
            repo.setOutputDirectory(tree.toString(), "Converted photos")
            val selected = repo.defaults.first()
            assertTrue(selected.autoSave)
            assertFalse(selected.autoReplaceOriginals)
            assertEquals(tree.toString(), selected.outputDirectoryUri)
            assertEquals(selected, decodeDefaults(selected.encode()))
            assertEquals("", decodeDefaults("{}").outputDirectoryUri)
            assertTrue(selected.outputToFolder)
            repo.setAutoReplaceOriginals(true)
            val replacing = repo.defaults.first()
            assertTrue(replacing.autoSave)
            assertTrue(replacing.autoReplaceOriginals)
            assertEquals(selected.outputDirectoryUri, replacing.outputDirectoryUri)
            assertEquals(selected.outputDirectoryName, replacing.outputDirectoryName)
            assertFalse(replacing.outputToFolder)
            assertEquals("", replacing.effectiveOutputDirectoryUri)
            assertEquals(replacing, decodeDefaults(replacing.encode()))
            repo.setDefaults(replacing)
            repo.setAutoReplaceOriginals(false)
            assertEquals(selected, repo.defaults.first())
            // Disabling saving also retains the folder for when saving is enabled again.
            repo.setAutoSave(false)
            assertEquals(selected.outputDirectoryUri, repo.defaults.first().outputDirectoryUri)
            repo.setAutoSave(true)
            assertEquals(selected, repo.defaults.first())
            repo.setOutputDirectory("", "")
            assertEquals("", repo.defaults.first().outputDirectoryUri)
        } finally { repo.setDefaults(original) }
    }

    @Test fun replacementToggleAlsoRestoresFolderChosenOnlyOnMainPage() = runBlocking {
        grant()
        val repo = app.settingsRepository
        val original = repo.defaults.first()
        val store = ViewModelStore()
        var collector: kotlinx.coroutines.Job? = null
        try {
            repo.setDefaults(ConversionDefaults(quality = 87))
            val vm = withContext(Dispatchers.Main) { HomeViewModel(app).also { store.put("home-folder", it) } }
            collector = launch(Dispatchers.Default) { vm.uiState.collect {} }
            withTimeout(10_000) { vm.uiState.first { it.defaults.quality == 87 } }
            withContext(Dispatchers.Main) { vm.setOutputDirectory(tree) }
            withTimeout(10_000) { vm.uiState.first { it.defaults.outputDirectoryUri == tree.toString() } }
            assertEquals("", repo.defaults.first().outputDirectoryUri)
            repo.setAutoReplaceOriginals(true)
            val replacing = withTimeout(10_000) { vm.uiState.first { it.defaults.autoReplaceOriginals } }.defaults
            assertEquals(tree.toString(), replacing.outputDirectoryUri)
            assertFalse(replacing.outputToFolder)
            repo.setAutoReplaceOriginals(false)
            val restored = withTimeout(10_000) { vm.uiState.first { !it.defaults.autoReplaceOriginals } }.defaults
            assertEquals(tree.toString(), restored.effectiveOutputDirectoryUri)
            assertEquals("Converted photos", restored.outputDirectoryName)
            assertTrue(restored.autoSave)
        } finally {
            collector?.cancel()
            withContext(Dispatchers.Main) { store.clear() }
            repo.setDefaults(original)
        }
    }
}

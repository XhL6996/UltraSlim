package app.ultraslim

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.convert.*
import app.ultraslim.data.*
import app.ultraslim.model.OutputFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class FileMetadataTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UltraSlimApp

    @Test fun nativeTimestampChangesOnlyMtimeAndDoesNotWriteBytes() {
        val file = File(app.cacheDir, "outputs/mtime-${UUID.randomUUID()}.jpg").apply { parentFile!!.mkdirs() }
        val bytes = byteArrayOf(1, 2, 3, 4)
        val time = 1_600_000_000_123L
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use {
                val before = android.system.Os.fstat(it.fileDescriptor).st_atime
                assertTrue(UltraHdrBridge.nativeSetModifiedTime(it.fd, time))
                assertEquals(before, android.system.Os.fstat(it.fileDescriptor).st_atime)
                assertEquals(time, file.lastModified())
                assertFalse(UltraHdrBridge.nativeSetModifiedTime(it.fd, -1))
            }
            assertArrayEquals(bytes, file.readBytes())
            // The same path used by SAF/gallery timestamp updates must also verify after fd close.
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            OutputFileTime.require(app, uri, time + 1234)
            assertEquals(time + 1234, file.lastModified())
            assertFalse(UltraHdrBridge.nativeSetModifiedTime(-1, time))
        } finally { file.delete() }
    }

    @Test fun pathTimestampReportsErrnoAndPreservesUnicodePathsAndAtime() {
        val file = File(app.cacheDir, "mtime-照片-📷-${UUID.randomUUID()}.jpg")
        val time = 1_600_000_000_123L
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val before = android.system.Os.stat(file.path).st_atime
            assertEquals(0, UltraHdrBridge.nativeSetPathModifiedTime(file.path.toByteArray(Charsets.UTF_8), time))
            assertEquals(time, file.lastModified())
            assertEquals(before, android.system.Os.stat(file.path).st_atime)
            assertEquals(android.system.OsConstants.ENOENT, UltraHdrBridge.nativeSetPathModifiedTime(
                (file.path + ".missing").toByteArray(Charsets.UTF_8), time))
            assertEquals(android.system.OsConstants.EINVAL, UltraHdrBridge.nativeSetPathModifiedTime(byteArrayOf(0), time))
        } finally { file.delete() }
    }

    @Test fun nativeConversionAndDeferredPublicationKeepInputMtimeInsteadOfCaptureDate() = runBlocking {
        val task = UUID.randomUUID().toString()
        val input = File(app.filesDir, "inputs/$task.jpg").apply { parentFile!!.mkdirs() }
        val originalTime = 1_600_000_000_123L
        var row: HistoryEntity? = null
        var published: Uri? = null
        app.conversionRepository.awaitReady()
        try {
            instrumentation.context.assets.open("minnie-320x240-yuv-icc.jpg").use { stream ->
                input.outputStream().use { stream.copyTo(it) }
            }
            assertTrue(input.setLastModified(originalTime))
            val source = FileProvider.getUriForFile(app, "${app.packageName}.files", input)
            assertEquals(originalTime, SourceMedia.modifiedTime(app, source))
            app.conversionRepository.addAll(listOf(QueueItem(task, source.toString(), "123456.jpeg", input.length(),
                status = QueueStatus.READY, batchId = task)))
            app.conversionRepository.flush()
            app.database.taskDao().putBatch(BatchEntity(task, "[\"$task\"]", ConversionDefaults(
                format = OutputFormat.JPEG, autoSave = false, onlySmaller = false, keepFilename = true).encode()))
            assertEquals(ConversionRunner.Outcome.COMPLETE,
                ConversionRunner(app, thermalStatus = { 0 }, setForeground = {}).run(task))
            row = requireNotNull(app.database.historyDao().byTask(task))
            val privateFile = File(row.outputUri)
            assertEquals("123456.jpg", row.displayName)
            assertEquals(originalTime, privateFile.lastModified())
            assertEquals(originalTime, OutputMetadataStore.read(privateFile)?.modifiedAt)
            // The durable receipt survives source modification and loss of access before saving.
            assertTrue(input.setLastModified(originalTime + 86_400_000))
            assertTrue(input.delete())
            val saved = OutputStore.save(app, row)
            assertEquals("SAVED", saved.saveState)
            published = Uri.parse(saved.outputUri)
            assertEquals(originalTime, SourceMedia.modifiedTime(app, published))
            assertFalse(privateFile.exists())
            assertNull(OutputMetadataStore.read(privateFile))
        } finally {
            published?.let { app.contentResolver.delete(it, null, null) }
            row?.let { OutputStore.delete(app, it.id) }
            app.conversionRepository.remove(task)
            app.conversionRepository.flush()
            input.delete()
        }
    }

    @Test fun galleryNameCollisionsHashInsteadOfAdoptingExistingIdenticalPhotos() {
        val file = File(app.cacheDir, "name-${UUID.randomUUID()}.jpg")
        val owned = mutableSetOf<Uri>()
        val name = "same-${UUID.randomUUID()}.jpg"
        try {
            instrumentation.context.assets.open("minnie-320x240-yuv-icc.jpg").use { stream ->
                file.outputStream().use { stream.copyTo(it) }
            }
            val first = requireNotNull(MediaStoreSaver.save(app, file, name, OutputFormat.JPEG, keepFilename = true))
            owned += first
            // A different conversion has a distinct private file/receipt even when bytes match.
            PublicationReceipt.delete(file)
            val second = requireNotNull(MediaStoreSaver.save(app, file, name, OutputFormat.JPEG, keepFilename = true))
            owned += second
            assertNotEquals(first, second)
            val expectedName = name.substringBeforeLast('.') + "_${requireNotNull(MediaStoreSaver.fingerprintOf(file)).sha256.take(8)}.jpg"
            app.contentResolver.query(second, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(expectedName, it.getString(0))
            }
            assertEquals(second, MediaStoreSaver.save(app, file, name, OutputFormat.JPEG, keepFilename = true))
        } finally {
            owned.forEach { app.contentResolver.delete(it, null, null) }
            PublicationReceipt.delete(file)
            file.delete()
        }
    }
}

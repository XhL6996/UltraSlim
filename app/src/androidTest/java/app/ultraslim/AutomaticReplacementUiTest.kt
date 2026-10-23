package app.ultraslim

import android.graphics.Bitmap
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.flow.first
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.convert.*
import app.ultraslim.data.*
import app.ultraslim.model.OutputFormat
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** End-to-end through the direct service, saved output, foreground coordinator and system dialog. */
class AutomaticReplacementUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun awaitAppWindow() {
        compose.waitUntil(5000) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.packageName?.toString() == compose.activity.packageName
        }
    }

    private fun confirmReview() {
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("replacement-confirm").fetchSemanticsNode() }.isSuccess
        }
        awaitAppWindow()
        compose.onNodeWithTag("replacement-confirm").performClick()
    }

    private fun runReplacement(approve: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = compose.activity.application as UltraSlimApp
        val id = UUID.randomUUID().toString()
        val input = File(app.cacheDir, "$id-source.jpg")
        val random = java.util.Random(42)
        val pixels = IntArray(256 * 256) { random.nextInt() or 0xff000000.toInt() }
        val image = Bitmap.createBitmap(pixels, 256, 256, Bitmap.Config.ARGB_8888)
        input.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 100, it) }; image.recycle()
        val originalDate = 1600000000000L
        // DATE_TAKEN is scanner-owned: seed real EXIF so publication can index it.
        androidx.exifinterface.media.ExifInterface(input).apply {
            setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, "2020:09:13 12:26:40")
            setAttribute(androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00")
            saveAttributes()
        }
        val album = "DCIM/UltraSlimReplacementTest/"
        val source = MediaStoreSaver.save(app, input, "$id-source.jpg", OutputFormat.JPEG,
            relativePath = album, dateTaken = originalDate)!!
        try {
            runBlocking {
                app.settingsRepository.setAutoReplaceOriginals(true)
                val repo = app.conversionRepository
                repo.awaitReady()
                repo.addAll(listOf(QueueItem(id, source.toString(), "$id.jpg", input.length(), status = QueueStatus.READY, batchId = id)))
                repo.flush()
                app.database.taskDao().putBatch(BatchEntity(id, "[\"$id\"]", ConversionDefaults(format = OutputFormat.JPEG,
                    quality = 70, autoReplaceOriginals = true).encode()))
            }
            compose.runOnUiThread { ConversionService.start(app, id) }
            confirmReview()
            var button: AccessibilityNodeInfo? = null
            compose.waitUntil(60_000) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                button = root?.findAccessibilityNodeInfosByViewId(if (approve) "android:id/button1" else "android:id/button2")?.firstOrNull()
                root?.packageName?.toString()?.contains("providers.media") == true && button != null
            }
            // Work arriving after this click must not inherit permission for another system dialog.
            runBlocking { app.database.replacementDao().insert(ReplacementEntity(9_000_001, "later", "content://later/1",
                "content://later-output/1", "a", "b", state = "READY")) }
            assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitUntil(15_000) {
                runBlocking {
                    val history = app.database.historyDao().byTask(id)
                    history != null && app.database.replacementDao().byId(history.id)?.state == if (approve) "DONE" else "CANCELLED"
                }
            }
            assertEquals(approve, MediaStoreTrashHelper.isOriginalTrashed(app, source))
            compose.waitForIdle()
            runBlocking { assertEquals(if (approve) "READY" else "CANCELLED",
                app.database.replacementDao().byId(9_000_001)?.state) }
            awaitAppWindow()
            runBlocking {
                val history = app.database.historyDao().byTask(id)!!
                assertEquals("SAVED", history.saveState)
                val metadata = SourceMedia.read(app, Uri.parse(history.outputUri))!!
                assertEquals(album, metadata.relativePath)
                assertEquals(originalDate, metadata.dateTaken)
                app.contentResolver.openInputStream(Uri.parse(history.outputUri))!!.use { assertTrue(it.read() >= 0) }
            }
        } finally {
            runBlocking {
                app.database.replacementDao().delete(9_000_001)
                app.database.taskDao().cancel(id)
                app.database.historyDao().byTask(id)?.let {
                    if (it.outputUri.startsWith("content://")) app.contentResolver.delete(Uri.parse(it.outputUri), null, null)
                    app.database.replacementDao().finish(listOf(it.id), "CANCELLED", "测试完成")
                    app.database.historyDao().deleteById(it.id)
                }
                app.settingsRepository.setAutoReplaceOriginals(false)
                app.conversionRepository.remove(id); app.conversionRepository.flush()
            }
            app.contentResolver.delete(source, null, null); input.delete()
        }
    }

    @Test fun cancelSystemRequestKeepsOriginalAndSavedOutput() = runReplacement(false)
    @Test fun approveSystemRequestTrashesOnlyTestOriginalAfterWorkerSuccess() = runReplacement(true)

    @Test fun largeReadyBatchWaitsForPublicationAndCanBeKeptWithoutAnySystemDialog() {
        val app = compose.activity.application as UltraSlimApp
        val rows = (1L..2050L).map { ReplacementEntity(10_000_000 + it, "bulk-review", "content://bulk/$it",
            "content://bulk-output/$it", "a", "b", state = "READY") }
        try {
            compose.runOnIdle { app.galleryPublicationRunning.value = true }
            runBlocking {
                app.settingsRepository.setAutoReplaceOriginals(true)
                app.database.withTransaction { rows.forEach { app.database.replacementDao().insert(it) } }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("replacement-review").assertDoesNotExist()
            compose.runOnIdle { app.galleryPublicationRunning.value = false }
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithTag("replacement-keep").fetchSemanticsNode() }.isSuccess
            }
            awaitAppWindow()
            runBlocking { assertEquals(2050, app.database.replacementDao().ready().first().size) }
            compose.onNodeWithTag("replacement-review").captureToImage().asAndroidBitmap().let { bitmap ->
                File(app.cacheDir, "replacement-review.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithTag("replacement-keep").performClick()
            compose.waitUntil(15_000) { runBlocking {
                rows.all { app.database.replacementDao().byId(it.historyId)?.state == "CANCELLED" }
            } }
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithTag("replacement-review").assertDoesNotExist()
            awaitAppWindow()
        } finally {
            compose.runOnIdle { app.galleryPublicationRunning.value = false }
            runBlocking {
                app.database.replacementDao().deleteAll(rows.map { it.historyId })
                app.settingsRepository.setAutoReplaceOriginals(false)
            }
        }
    }

    @Test fun journalsForExternallyRemovedOriginalsDoNotReopenSystemConfirmation() {
        val app = compose.activity.application as UltraSlimApp
        for (trash in listOf(true, false)) {
            compose.runOnIdle { app.galleryPublishingAllowed.value = false }
            runBlocking {
                AutoReplacementTest().fixture { history, source, _ ->
                    AutoReplacement.stage(app, history, AutoReplacement.fingerprint(app, source))
                    AutoReplacement.saved(app, OutputStore.save(app, history))
                    if (trash) app.contentResolver.update(source, android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.IS_TRASHED, 1)
                    }, null, null) else app.contentResolver.delete(source, null, null)
                    compose.runOnIdle { app.galleryPublishingAllowed.value = true }
                    confirmReview()
                    compose.waitUntil(10_000) {
                        runBlocking { app.database.replacementDao().byId(history.id)?.state == if (trash) "DONE" else "SKIPPED" }
                    }
                    awaitAppWindow()
                }
            }
        }
    }

}

package app.ultraslim

import android.graphics.Bitmap
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

    private fun runReplacement(approve: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = compose.activity.application as UltraSlimApp
        val id = UUID.randomUUID().toString()
        val input = File(app.cacheDir, "$id-source.jpg")
        val random = java.util.Random(42)
        val pixels = IntArray(256 * 256) { random.nextInt() or 0xff000000.toInt() }
        val image = Bitmap.createBitmap(pixels, 256, 256, Bitmap.Config.ARGB_8888)
        input.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 100, it) }; image.recycle()
        val source = MediaStoreSaver.save(app, input, "$id-source.jpg", OutputFormat.JPEG)!!
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
            var button: AccessibilityNodeInfo? = null
            compose.waitUntil(60_000) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                button = root?.findAccessibilityNodeInfosByViewId(if (approve) "android:id/button1" else "android:id/button2")?.firstOrNull()
                root?.packageName?.toString()?.contains("providers.media") == true && button != null
            }
            assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitUntil(15_000) {
                runBlocking {
                    val history = app.database.historyDao().byTask(id)
                    history != null && app.database.replacementDao().byId(history.id)?.state == if (approve) "DONE" else "CANCELLED"
                }
            }
            assertEquals(approve, MediaStoreTrashHelper.isOriginalTrashed(app, source))
            runBlocking {
                val history = app.database.historyDao().byTask(id)!!
                assertEquals("SAVED", history.saveState)
                app.contentResolver.openInputStream(Uri.parse(history.outputUri))!!.use { assertTrue(it.read() >= 0) }
            }
        } finally {
            runBlocking {
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
}

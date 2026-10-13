package app.ultraslim

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.bridge.ProbeResult
import app.ultraslim.convert.QueueItem
import app.ultraslim.convert.QueueStatus
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Validate the real navigation, scrolling queue and fixed conversion action together. */
class QueueActionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as UltraSlimApp
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ids = List(6) { UUID.randomUUID().toString() }
    private var guarded = false
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
    }
    @Before fun setup() = runBlocking {
        Assume.assumeTrue(android.os.Build.MODEL.contains("sdk"))
        guarded = true
        shell("wm density 160")
        app.conversionRepository.awaitReady()
        val probe = ProbeResult(0, "", false, 0, 4096, 3072, 0, 0, false, 1f, 1f)
        app.conversionRepository.addAll(ids.map {
            QueueItem(it, "content://layout/$it", "布局测试.jpg", 5_000_000, probe, QueueStatus.READY)
        })
        app.conversionRepository.flush()
    }
    @After fun cleanup() = runBlocking {
        if (guarded) {
            app.conversionRepository.removeIds(ids)
            app.conversionRepository.flush()
            shell("wm size reset"); shell("wm density reset")
            shell("settings put system font_scale 1.0")
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle(); Thread.sleep(500)
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            File(app.filesDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun folderImportExplainsRecursiveSelectionAndRetainsTheChoice() {
        shell("wm size 400x900")
        compose.waitUntil(10_000) { compose.onRoot().fetchSemanticsNode().boundsInRoot.width.toInt() == 400 }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("queue-import-actions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-import-actions"))
        compose.onNodeWithContentDescription("更多导入方式").performClick()
        compose.onNodeWithText("文件夹导入").performClick()
        compose.onNodeWithText("包含子文件夹").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("选择文件夹").assertIsDisplayed()
        capture("folder-import-options")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("更多导入方式").performClick()
        compose.onNodeWithText("文件夹导入").performClick()
        compose.onNodeWithText("包含子文件夹").assertIsOff()
        compose.onNodeWithText("取消").performClick()
    }
    @Test fun importActionsScrollWhilePrimaryActionStaysAboveNavigation() {
        for ((width, font) in listOf(400 to 1f, 320 to 2f, 840 to 1f)) {
            shell("settings put system font_scale $font")
            shell("wm size ${width}x900")
            compose.waitUntil(10_000) { compose.onRoot().fetchSemanticsNode().boundsInRoot.width.toInt() == width }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("转换 6 张照片").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-import-actions"))
            compose.onNodeWithText("添加照片").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithContentDescription("更多导入方式").assertIsDisplayed().performClick()
            compose.onNodeWithText("相册导入").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("文件夹导入").assertIsDisplayed().assertIsEnabled()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("conversion-action").assertIsDisplayed().assertIsEnabled()
            val action = compose.onNodeWithTag("conversion-action").fetchSemanticsNode().boundsInRoot
            if (width < 600) {
                val nav = compose.onNodeWithText("历史").fetchSemanticsNode().boundsInRoot
                assertTrue("Conversion action must be separate from navigation", action.bottom + 12 <= nav.top)
            }
            capture("queue-ready-$width-$font")
            compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-row-${ids.last()}"))
            compose.onNodeWithTag("conversion-action").assertIsDisplayed()
            val after = compose.onNodeWithTag("conversion-action").fetchSemanticsNode().boundsInRoot
            assertEquals("Scrolling must not move the primary action", action.bottom, after.bottom, 1f)
            compose.runOnIdle { app.conversionRepository.update(ids.first()) { it.copy(status = QueueStatus.CONVERTING) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("停止转换").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("queue-import-actions").assertDoesNotExist()
            compose.onNodeWithTag("conversion-action").assertIsDisplayed().assertIsEnabled()
            capture("queue-running-$width-$font")
            compose.runOnIdle { app.conversionRepository.update(ids.first()) { it.copy(status = QueueStatus.READY) } }
        }
    }
}

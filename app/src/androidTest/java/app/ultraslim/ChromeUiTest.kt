package app.ultraslim

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Capture actual system UI too: Compose root screenshots cannot catch status-bar icon regressions. */
class ChromeUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as UltraSlimApp
    private var guarded = false
    private lateinit var oldTheme: ThemeSettings
    private lateinit var oldDefaults: ConversionDefaults
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
    }
    @Before fun setup() {
        Assume.assumeTrue(deviceUiTestsAllowed())
        guarded = true
        runBlocking {
            oldTheme = app.settingsRepository.theme.first()
            oldDefaults = app.settingsRepository.defaults.first()
            app.settingsRepository.setDynamicColor(true)
            app.settingsRepository.setAutoReplaceOriginals(false)
        }
        shell("settings put system font_scale 1.0")
        shell("wm density 160")
    }
    @After fun cleanup() {
        if (!guarded) return
        runBlocking {
            app.settingsRepository.setThemeMode(oldTheme.themeMode)
            app.settingsRepository.setDynamicColor(oldTheme.dynamicColor)
            app.settingsRepository.setDefaults(oldDefaults)
        }
        shell("wm size reset"); shell("wm density reset")
        shell("settings put system font_scale 1.0")
    }
    private fun resize(width: Int, height: Int) {
        shell("wm size ${width}x$height")
        compose.waitUntil(10_000) { compose.onRoot().fetchSemanticsNode().boundsInRoot.width.toInt() == width }
        compose.waitForIdle()
        // WindowManager resize animation is outside the Compose idling clock.
        Thread.sleep(800)
    }
    private fun theme(mode: ThemeMode) {
        runBlocking { app.settingsRepository.setThemeMode(mode) }
        compose.waitForIdle()
    }
    private fun capture(name: String, verifyChrome: Boolean = false) {
        compose.waitForIdle()
        Thread.sleep(300)
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            File(app.filesDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (verifyChrome) {
                val top = WindowInsetsCompat.toWindowInsetsCompat(compose.activity.window.decorView.rootWindowInsets)
                    .getInsets(WindowInsetsCompat.Type.statusBars()).top
                assertTrue("Status bar must be present in this full-window test", top > 0)
                // Empty space away from clock, camera and radio/battery icons, on both sides of the rail boundary.
                val y = top / 2
                val left = bitmap.getPixel(4, y)
                val main = bitmap.getPixel(bitmap.width / 3, y)
                assertEquals("Rail and app bar split the status bar background", left, main)
                val toolbar = bitmap.getPixel(bitmap.width / 3, top + 8)
                assertEquals("Toolbar and status bar must form one surface", main, toolbar)
            }
            bitmap.recycle()
        }
    }
    @Test fun darkFoldSettingsHaveContinuousChromeBeforeAndAfterScroll() {
        theme(ThemeMode.DARK)
        // Similar aspect ratio to the reported unfolded screen, with enough logical width for two panels.
        resize(750, 828)
        capture("polish-fold-home-dark", true)
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithTag("workspace-expanded").assertExists()
        compose.onNodeWithText("外观与显示").assertIsDisplayed()
        capture("polish-fold-settings-dark", true)
        compose.onNodeWithText("保留 GPS 位置信息").performScrollTo().assertIsDisplayed()
        capture("polish-fold-settings-scrolled-dark", true)
        compose.onNodeWithText("保留 GPS 位置信息").performClick()
        compose.waitUntil(5_000) { runBlocking { app.settingsRepository.defaults.first().keepLocation != oldDefaults.keepLocation } }
        if (oldDefaults.keepLocation) compose.onNodeWithText("保留 GPS 位置信息").assertIsOff()
        else compose.onNodeWithText("保留 GPS 位置信息").assertIsOn()
    }
    @Test fun phoneSettingsAndConfirmationStayAccessible() {
        theme(ThemeMode.LIGHT)
        resize(420, 900)
        capture("polish-phone-home-light", true)
        theme(ThemeMode.DARK)
        capture("polish-phone-home-dark", true)
        theme(ThemeMode.LIGHT)
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithTag("workspace-compact").assertExists()
        capture("polish-phone-settings-light", true)
        compose.onNodeWithText("校验后自动替换原图").performScrollTo().performClick()
        compose.onNodeWithText("开启校验后自动替换？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertFalse(runBlocking { app.settingsRepository.defaults.first().autoReplaceOriginals })
        compose.onNodeWithText("动态取色").performScrollTo().assertIsDisplayed()
        capture("polish-phone-appearance-light", true)
    }
}

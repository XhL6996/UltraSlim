package app.ultraslim

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.ultraslim.data.ConversionDefaults
import app.ultraslim.data.ThemeMode
import app.ultraslim.ui.components.ConversionOptionsPanel
import app.ultraslim.ui.components.OptionsCallbacks
import app.ultraslim.ui.theme.AppTheme
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Exercise the real panel with both footer callbacks: older previews omitted this entire section. */
class DefaultOptionsActionsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private data class Case(val width: Int = 320, val font: Float = 1f, val enabled: Boolean = true,
        val save: Boolean = true, val reset: Boolean = true)
    private var case by mutableStateOf(Case())
    private var saves = 0
    private var resets = 0

    private var emulatorVerified = false
    private fun shell(command: String) = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
    }
    @After fun cleanup() {
        if (emulatorVerified) { shell("wm size reset"); shell("wm density reset") }
    }
    @Before fun setup() {
        Assume.assumeTrue(deviceUiTestsAllowed())
        emulatorVerified = true
        shell("wm size 800x1200"); shell("wm density 160")
        compose.waitUntil(10_000) { compose.onRoot().fetchSemanticsNode().boundsInRoot.width.toInt() == 800 }

        // A bounded panel in a real window; override density only in this test composition.
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, case.font)) {
                    AppTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                        key(case) {
                            Surface {
                                Column(Modifier.width(case.width.dp).fillMaxHeight()
                                    .verticalScroll(rememberScrollState()).testTag("options-panel")) {
                                    ConversionOptionsPanel(ConversionDefaults(), OptionsCallbacks(
                                        {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                                        onSaveAsDefault = if (case.save) ({ saves++ }) else null,
                                        onResetToDefault = if (case.reset) ({ resets++ }) else null,
                                    ), enabled = case.enabled)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun checkLabel(label: String) {
        val node = compose.onNodeWithText(label, useUnmergedTree = true)
        node.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("$label must have a text layout", layouts.isNotEmpty())
        assertTrue("$label became vertical: ${case}", layouts.single().lineCount == 1)
        val layout = layouts.single()
        // A tightly measured button can keep a wider paragraph constraint than its text node.
        // Check actual line extents and ellipsis, not the paragraph's unused right-hand space.
        for (line in 0 until layout.lineCount) {
            assertFalse("$label was ellipsized: $case", layout.isLineEllipsized(line))
            assertTrue("$label text exceeds its button: $case", layout.getLineRight(line) <= layout.size.width + 1f)
        }
        assertTrue("$label text clipped vertically: $case", layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 1f)

        val bounds = node.fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("options-panel").fetchSemanticsNode().boundsInRoot
        assertEquals("Window must really provide the requested panel width", case.width.toFloat(), panel.width, 1f)
        assertTrue(bounds.left >= panel.left && bounds.right <= panel.right + 1)
    }
    @Test fun narrowPanelsAndLargeTextNeverSqueezeActionsIntoVerticalLabels() {
        for (width in listOf(280, 320, 400, 640)) for (font in listOf(1f, 1.3f, 2f)) {
            compose.runOnIdle { case = Case(width, font) }
            checkLabel("重置为默认")
            checkLabel("保存为全局默认")
            compose.onNodeWithText("重置为默认").performScrollTo().performClick()
            compose.onNodeWithText("保存为全局默认").performScrollTo().performClick()
            Thread.sleep(350) // Let the platform-rendered ripple settle before recording.
            compose.onNodeWithTag("default-options-actions").performScrollTo()
            compose.onNodeWithTag("default-options-actions").captureToImage().asAndroidBitmap().also { bitmap ->
                File(compose.activity.filesDir, "controls-${width}-${font}.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        compose.runOnIdle { assertEquals(12, saves); assertEquals(12, resets) }
    }
    @Test fun absentActionsAndDisabledConversionAreRespected() {
        compose.runOnIdle { case = Case(enabled = false) }
        compose.onNodeWithText("保存为全局默认").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("重置为默认").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { case = Case(reset = false) }
        checkLabel("保存为全局默认")
        compose.onNodeWithText("重置为默认").assertDoesNotExist()
        compose.runOnIdle { case = Case(save = false) }
        checkLabel("重置为默认")
        compose.onNodeWithText("保存为全局默认").assertDoesNotExist()
        compose.runOnIdle { case = Case(save = false, reset = false) }
        compose.onNodeWithTag("default-options-actions").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, saves); assertEquals(0, resets) }
    }
}

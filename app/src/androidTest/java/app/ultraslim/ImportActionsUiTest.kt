package app.ultraslim

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.ultraslim.ui.components.ImportActions
import app.ultraslim.ui.theme.AppTheme
import org.junit.*
import org.junit.Assert.*

class ImportActionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var prominent by mutableStateOf(true)
    private var enabled by mutableStateOf(true)
    private var largeText by mutableStateOf(false)
    private var picks = 0
    private var albums = 0
    private var folders = 0
    @Before fun setup() {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, if (largeText) 2f else 1f)) {
                    AppTheme {
                        Surface {
                            Box(Modifier.width(240.dp).verticalScroll(rememberScrollState()).padding(top = 32.dp, bottom = 24.dp)) {
                                ImportActions({ picks++ }, { albums++ }, { folders++ },
                                    prominent = prominent, enabled = enabled)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    @Test fun compactAndHeroActionsKeepLabelsAndDispatchAllThreeSources() {
        for (hero in listOf(true, false)) for (large in listOf(false, true)) {
            compose.runOnIdle { prominent = hero; largeText = large }
            val primary = compose.onNodeWithText(if (hero) "选择照片" else "添加照片")
            primary.performScrollTo().assertIsDisplayed()
            assertSingleLine(primary)
            primary.performClick()
            if (hero) {
                compose.onNodeWithContentDescription("更多导入方式").assertDoesNotExist()
                for (label in listOf("相册导入", "文件夹导入")) {
                    val source = compose.onNodeWithText(label)
                    source.performScrollTo().assertIsDisplayed()
                    assertSingleLine(source)
                    source.performClick()
                }
            } else {
                compose.onNodeWithText("相册导入").assertDoesNotExist()
                compose.onNodeWithContentDescription("更多导入方式").performClick()
                compose.onNodeWithText("相册导入").assertIsDisplayed().performClick()
                compose.onNodeWithText("相册导入").assertDoesNotExist()
                compose.onNodeWithContentDescription("更多导入方式").performClick()
                compose.onNodeWithText("文件夹导入").assertIsDisplayed().performClick()
                compose.onNodeWithText("文件夹导入").assertDoesNotExist()
            }
        }
        compose.runOnIdle { assertEquals(4, picks); assertEquals(4, albums); assertEquals(4, folders) }
    }
    private fun assertSingleLine(node: SemanticsNodeInteraction) {
        val layout = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        assertEquals("Import title must stay on one line", 1, layout.single().lineCount)
        val result = layout.single()
        // Compose rounds the intrinsic Text node width to whole pixels; allow that rounding only.
        assertTrue("Import title exceeds its bounds: prominent=$prominent large=$largeText",
            result.getLineRight(0) <= result.size.width + 1f)
        assertFalse("Import title height must not clip", result.didOverflowHeight)
    }
    @Test fun disablingImportDismissesMenuAndBlocksBothButtons() {
        compose.runOnIdle { prominent = false }
        compose.onNodeWithContentDescription("更多导入方式").performClick()
        compose.onNodeWithText("文件夹导入").assertIsDisplayed()
        compose.runOnIdle { enabled = false }
        compose.onNodeWithText("文件夹导入").assertDoesNotExist()
        compose.onNodeWithText("添加照片").assertIsNotEnabled()
        compose.onNodeWithContentDescription("更多导入方式").assertIsNotEnabled()
        compose.runOnIdle { prominent = true }
        for (label in listOf("选择照片", "相册导入", "文件夹导入")) {
            compose.onNodeWithText(label).assertIsNotEnabled()
        }
    }
}

package app.ultraslim

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.ultraslim.ui.components.CommitSwipeRow
import app.ultraslim.ui.settings.SettingsScreen
import app.ultraslim.ui.theme.AppTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GestureRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var commits = 0
    private fun row(right: Boolean = false, enabled: Boolean = true) {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                AppTheme {
                    CommitSwipeRow(Modifier.width(320.dp).testTag("row"), enabled, right,
                        onCommit = { commits++ }, background = {}) {
                        Box(Modifier.fillMaxWidth().height(90.dp)) { Text("滑动测试") }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
    @Test fun crossingThresholdThenCancellingNeverCommits() {
        row()
        compose.onNodeWithTag("row").performTouchInput {
            down(Offset(width * .9f, height * .5f))
            moveTo(Offset(width * .15f, height * .5f), 300)
            cancel()
        }
        compose.runOnIdle { assertEquals(0, commits) }
        compose.onNodeWithTag("row").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, commits) }
    }
    @Test fun slidingBackBeforeLiftingFingerAndFastShortFlingDoNotCommit() {
        row()
        compose.onNodeWithTag("row").performTouchInput {
            down(Offset(width * .9f, height * .5f))
            moveTo(Offset(width * .1f, height * .5f), 300)
            moveTo(Offset(width * .85f, height * .5f), 300)
            up()
        }
        compose.onNodeWithTag("row").performTouchInput {
            swipe(Offset(width * .8f, height * .5f), Offset(width * .55f, height * .5f), 60)
        }
        compose.runOnIdle { assertEquals(0, commits) }
    }
    @Test fun rightSwipeCanBeRepeatedAfterConfirmationCancellation() {
        row(right = true)
        repeat(2) { compose.onNodeWithTag("row").performTouchInput { swipeRight() } }
        compose.runOnIdle { assertEquals(2, commits) }
    }
    @Test fun convertingRowCannotBeDismissed() {
        row(enabled = false)
        compose.onNodeWithTag("row").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(0, commits) }
    }
    @Test fun upwardDiagonalScrollDoesNotRemoveRows() {
        var scrolled = false
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                val state = rememberLazyListState()
                scrolled = state.firstVisibleItemScrollOffset > 0 || state.firstVisibleItemIndex > 0
                LazyColumn(Modifier.fillMaxSize().testTag("list"), state = state) {
                    items(30) { i ->
                        CommitSwipeRow(onCommit = { commits++ }, background = {}) {
                            Box(Modifier.fillMaxWidth().height(100.dp)) { Text("照片 $i") }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("list").performTouchInput {
            swipe(Offset(width * .7f, height * .85f), Offset(width * .5f, height * .2f), 400)
        }
        compose.runOnIdle { assertTrue(scrolled); assertEquals(0, commits) }
    }
    @Test fun autoReplacementRequiresConfirmationAndCanBeDisabled() {
        val app = compose.activity.application as UltraSlimApp
        runBlocking { app.settingsRepository.setAutoReplaceOriginals(false) }
        compose.activity.runOnUiThread { compose.activity.setContent { AppTheme { SettingsScreen {} } } }
        val option = compose.onNodeWithText("校验后自动替换原图")
        option.performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("开启校验后自动替换？").assertExists()
        compose.onNodeWithText("取消").performClick()
        option.assertIsOff().performClick()
        compose.onNodeWithText("开启", substring = false).performClick()
        compose.waitUntil(5000) { runBlocking { app.settingsRepository.defaults.first().autoReplaceOriginals } }
        option.assertIsOn().performClick()
        compose.waitUntil(5000) { runBlocking { !app.settingsRepository.defaults.first().autoReplaceOriginals } }
    }
}

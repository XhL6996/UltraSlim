package app.ultraslim

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.ultraslim.bridge.ProbeResult
import app.ultraslim.convert.QueueItem
import app.ultraslim.convert.QueueStatus
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class QueueUndoUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val ids = (0..2).map { UUID.randomUUID().toString() }
    private val app get() = compose.activity.application as UltraSlimApp
    @Before fun seed() = runBlocking {
        val repo = app.conversionRepository; repo.awaitReady()
        val probe = ProbeResult(0, "", false, 0, 100, 100, 0, 0, false, 1f, 1f)
        repo.addAll(ids.map { QueueItem(it, "content://gesture/$it", "撤销测试.jpg", 1000, probe, QueueStatus.READY) })
        repo.flush()
    }
    @After fun clean() = runBlocking { app.conversionRepository.removeIds(ids); app.conversionRepository.flush() }
    @Test fun consecutiveSwipesUndoTogetherAndRowsRemainSwipeable() {
        fun swipe(id: String) {
            val count = app.conversionRepository.queue.value.count { it.status == QueueStatus.READY }
            // Home intentionally samples queue changes; wait for the rendered state, not just the repository.
            compose.waitUntil(5000) { compose.onAllNodesWithText("转换 $count 张照片").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("queue-list").performScrollToNode(hasTestTag("queue-row-$id"))
            compose.onNodeWithTag("queue-row-$id").performScrollTo().performTouchInput {
                // Keep the gesture inside the visible card and away from system edges/snackbar.
                swipe(androidx.compose.ui.geometry.Offset(width * .8f, height * .25f),
                    androidx.compose.ui.geometry.Offset(width * .1f, height * .25f), 350)
            }
            compose.waitUntil(5000) { app.conversionRepository.queue.value.none { it.id == id } }
        }
        swipe(ids[0]); swipe(ids[1])
        compose.onNodeWithText("已移除 2 张照片").assertExists()
        compose.onNodeWithText("撤销").performClick()
        compose.waitUntil(5000) { app.conversionRepository.queue.value.count { it.id in ids } == 3 }
        assertEquals(ids, app.conversionRepository.queue.value.filter { it.id in ids }.map { it.id })
        swipe(ids[0])
        compose.onNodeWithText("撤销").performClick()
        compose.waitUntil(5000) { app.conversionRepository.queue.value.any { it.id == ids[0] } }
    }
}

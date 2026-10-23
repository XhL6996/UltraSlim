package app.ultraslim

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.convert.QueueItem
import app.ultraslim.convert.QueueStatus
import app.ultraslim.data.BATCH_SNAPSHOT_ERROR
import app.ultraslim.data.BatchEntity
import app.ultraslim.ui.home.HomeViewModel
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class HomeSnapshotRecoveryTest {
    @Test fun openingHomeWithBrokenSettingsFailsPendingItemsAndKeepsCompletedResults() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UltraSlimApp
        check(app.packageName in setOf("app.ultraslim.verifyqa", "app.ultraslim.optqa"))
        val repo = app.conversionRepository
        repo.awaitReady()
        val batch = UUID.randomUUID().toString()
        val pending = "$batch-pending"
        val done = "$batch-done"
        val unrelated = "$batch-unrelated"
        val store = ViewModelStore()
        repo.addAll(listOf(
            QueueItem(pending, "content://test/$pending", "pending.jpg", 100, status = QueueStatus.READY, batchId = batch),
            QueueItem(done, "content://test/$done", "done.jpg", 100, status = QueueStatus.DONE, batchId = batch),
            QueueItem(unrelated, "content://test/$unrelated", "unrelated.jpg", 100, status = QueueStatus.READY, batchId = "other-batch"),
        ))
        repo.flush()
        app.database.taskDao().putBatch(BatchEntity(batch, "[\"$pending\",\"$done\",\"$unrelated\"]", "{broken",
            createdAt = System.currentTimeMillis() + 1000))
        try {
            withContext(Dispatchers.Main) { store.put("recovery", HomeViewModel(app)) }
            withTimeout(10_000) { repo.queue.first { rows -> rows.any { it.id == pending && it.status == QueueStatus.FAILED } } }
            repo.flush()
            assertEquals(BATCH_SNAPSHOT_ERROR, repo.find(pending)?.error)
            assertEquals(QueueStatus.DONE, repo.find(done)?.status)
            assertNull(repo.find(done)?.error)
            assertEquals(QueueStatus.READY, repo.find(unrelated)?.status)
            assertNull(repo.find(unrelated)?.error)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            repo.removeIds(listOf(pending, done, unrelated)); repo.flush()
            app.database.openHelper.writableDatabase.execSQL("DELETE FROM batches WHERE id = ?", arrayOf(batch))
        }
    }
}

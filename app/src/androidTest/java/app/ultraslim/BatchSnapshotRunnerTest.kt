package app.ultraslim

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ultraslim.convert.*
import app.ultraslim.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BatchSnapshotRunnerTest {
    private val app get() = ApplicationProvider.getApplicationContext<UltraSlimApp>()

    @Test fun invalidArraysCannotBecomeAnEmptySuccessfulBatch() {
        val options = ConversionDefaults().encode()
        for (ids in listOf("not-json", "[]", "{}", "[null]", "[3]", "[\"\"]", "[\"a\",3]", "[\"a\",\"a\"]")) {
            assertFalse(ids, decodeBatchSnapshot(BatchEntity("bad", ids, options)).isValid)
        }
        assertEquals(listOf("a"), decodeBatchSnapshot(BatchEntity("bad", "[\"a\",null,3]", options)).itemIds)
        assertTrue(decodeBatchSnapshot(BatchEntity("valid", "[\"a\"]", "{}")).isValid)
        assertFalse(decodeBatchSnapshot(BatchEntity("bad", "[\"a\"]", "not-json")).isValid)
    }

    @Test fun corruptRunnerSnapshotFailsPendingItemsWithoutTouchingCompletedResults() = runBlocking {
        app.conversionMutex.withLock {
            val repo = app.conversionRepository
            repo.awaitReady()
            val batchId = "bad-snapshot-${UUID.randomUUID()}"
            val pending = "$batchId-pending"
            val done = "$batchId-done"
            val skipped = "$batchId-skipped"
            val unrelated = "$batchId-unrelated"
            val items = listOf(
                QueueItem(pending, "content://test/$pending", "pending.jpg", 1, status = QueueStatus.READY, batchId = batchId),
                QueueItem(done, "content://test/$done", "done.jpg", 1, status = QueueStatus.DONE, batchId = batchId, outputUri = "content://output/safe"),
                QueueItem(skipped, "content://test/$skipped", "skipped.jpg", 1, status = QueueStatus.SKIPPED, batchId = batchId),
                QueueItem(unrelated, "content://test/$unrelated", "unrelated.jpg", 1, status = QueueStatus.READY, batchId = "other-batch"),
            )
            repo.addAll(items)
            try {
                app.database.taskDao().putBatch(BatchEntity(batchId, "[\"$pending\",\"$unrelated\",null]", ConversionDefaults().encode()))
                val runner = ConversionRunner(app, thermalStatus = { error("Corrupt batches must not reach thermal checks") }) {
                    error("Corrupt batches must fail before starting foreground work")
                }
                assertEquals(ConversionRunner.Outcome.FAILED, runner.run(batchId))
                assertEquals(QueueStatus.FAILED, repo.find(pending)?.status)
                assertEquals(BATCH_SNAPSHOT_ERROR, repo.find(pending)?.error)
                assertEquals(items[1], repo.find(done))
                assertEquals(items[2], repo.find(skipped))
                assertEquals(items[3], repo.find(unrelated))
                assertEquals(QueueStatus.FAILED, app.database.taskDao().all().first { it.id == pending }.status)
            } finally {
                app.database.taskDao().cancel(batchId)
                repo.removeIds(items.map { it.id }); repo.flush()
            }
        }
    }

    @Test fun exhaustedThermalWaitPausesReadyItemAndNeverMarksCompletedItemHot() = runBlocking {
        app.conversionMutex.withLock {
            val repo = app.conversionRepository
            repo.awaitReady()
            val batchId = "thermal-${UUID.randomUUID()}"
            val done = "$batchId-done"
            val pending = "$batchId-pending"
            val completed = QueueItem(done, "content://test/$done", "done.jpg", 1, status = QueueStatus.DONE, batchId = batchId)
            repo.addAll(listOf(completed, QueueItem(pending, "content://test/$pending", "pending.jpg", 1,
                status = QueueStatus.READY, batchId = batchId)))
            try {
                app.database.taskDao().putBatch(BatchEntity(batchId, "[\"$done\",\"$pending\"]", ConversionDefaults().encode()))
                val runner = ConversionRunner(app, thermalStatus = { 3 }) {}
                assertEquals(ConversionRunner.Outcome.COOLING, runner.run(batchId))
                assertEquals(completed, repo.find(done))
                assertEquals(ThermalPhase.COOLING, app.conversionThermalState.value?.phase)
                assertEquals(105, app.conversionThermalState.value?.remainingSeconds)
                assertEquals(ConversionRunner.Outcome.PAUSED, runner.run(batchId, ConversionThermalPolicy.MAX_RESUMES))
                assertEquals(QueueStatus.READY, repo.find(pending)?.status)
                assertTrue(repo.find(pending)?.error.orEmpty().contains("点击继续"))
                assertEquals(ThermalPhase.PAUSED, app.conversionThermalState.value?.phase)
                assertNull(app.database.historyDao().byTask(pending))
                repo.remove(pending)
                val allDoneRunner = ConversionRunner(app, thermalStatus = { error("Completed batches need no thermal check") }) {}
                assertEquals(ConversionRunner.Outcome.COMPLETE, allDoneRunner.run(batchId))
                assertEquals(completed, repo.find(done))
                assertNull(app.conversionThermalState.value)
            } finally {
                app.conversionThermalState.value = null
                app.database.taskDao().cancel(batchId)
                repo.removeIds(listOf(done, pending)); repo.flush()
            }
        }
    }
}

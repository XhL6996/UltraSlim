package app.ultraslim

import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.convert.OutputStore
import app.ultraslim.convert.QueueItem
import app.ultraslim.convert.QueueStatus
import app.ultraslim.data.HistoryEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistoryDeletionCoordinationTest {
    @Test fun deletionPreservesRecordAndFileWhileServiceIsStartingOrConversionOwnsLock() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UltraSlimApp
        check(app.packageName in setOf("app.ultraslim.verifyqa", "app.ultraslim.optqa")) { "Run in an isolated verification QA package" }
        app.conversionRepository.awaitReady()
        val task = UUID.randomUUID().toString()
        val file = File(app.filesDir, "outputs/$task.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val dao = app.database.historyDao()
        val id = dao.insert(HistoryEntity(displayName = "guard.jpg", inputUri = "content://test/$task",
            outputUri = file.path, inputBytes = 10, outputBytes = 3, width = 1, height = 1,
            gainmapWidth = 0, gainmapHeight = 0, hadGainMap = false, format = "JPEG", inputFormat = "JPEG",
            durationMs = 1.0, maxContentBoost = 1f, createdAt = System.currentTimeMillis(), status = "DONE",
            taskId = task, saveState = "PENDING"))
        app.conversionRepository.addAll(listOf(QueueItem(
            id = task, uri = "content://test/$task", displayName = "guard.jpg", sizeBytes = 10,
            status = QueueStatus.DONE, outputUri = file.path, historyId = id)))
        app.conversionRepository.flush()
        try {
            app.conversionServiceRunning.value = true
            try {
                OutputStore.delete(app, id)
                fail("Queued foreground service must prevent deletion")
            } catch (_: OutputStore.ConversionInProgress) {
                assertNotNull(dao.byId(id))
                assertTrue(file.exists())
            } finally {
                app.conversionServiceRunning.value = false
            }
            app.conversionMutex.lock()
            try {
                try {
                    OutputStore.deleteBatch(app, listOf(id))
                    fail("The conversion lock must prevent deletion even before state emission")
                } catch (_: OutputStore.ConversionInProgress) {
                    assertNotNull(dao.byId(id))
                    assertTrue(file.exists())
                }
            } finally {
                app.conversionMutex.unlock()
            }
            OutputStore.delete(app, id)
            assertNull(dao.byId(id))
            assertFalse(file.exists())
            assertNull(app.conversionRepository.find(task))
            assertTrue(app.database.taskDao().all().none { it.id == task })
            assertFalse(app.conversionMutex.isLocked)
        } finally {
            app.conversionServiceRunning.value = false
            dao.deleteById(id)
            app.conversionRepository.remove(task)
            app.conversionRepository.flush()
            file.delete()
        }
    }
}

package app.ultraslim

import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.convert.OutputStore
import app.ultraslim.convert.QueueItem
import app.ultraslim.convert.QueueStatus
import app.ultraslim.data.HistoryEntity
import app.ultraslim.ui.components.PreviewCache
import app.ultraslim.ui.home.HomeViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StorageAndPreviewTest {
    @Test fun removingCompletedTaskKeepsHistorySourceReadable() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UltraSlimApp
        val repo = app.conversionRepository
        repo.awaitReady()
        val id = UUID.randomUUID().toString()
        val input = File(app.filesDir, "inputs/$id").apply { parentFile!!.mkdirs(); writeText("retained-source") }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", input)
        val record = HistoryEntity(displayName = "test.jpg", inputUri = uri.toString(), outputUri = "/missing/$id",
            inputBytes = input.length(), outputBytes = 1, width = 1, height = 1, gainmapWidth = 0, gainmapHeight = 0,
            hadGainMap = false, format = "JPEG", inputFormat = "JPEG", durationMs = 1.0, maxContentBoost = 1f,
            createdAt = System.currentTimeMillis(), status = "DONE", taskId = id, saveState = "PENDING")
        val historyId = app.database.historyDao().insert(record)
        val store = ViewModelStore()
        try {
            repo.addAll(listOf(QueueItem(id, uri.toString(), "source.jpg", input.length(), status = QueueStatus.DONE, historyId = historyId)))
            repo.flush()
            instrumentation.runOnMainSync {
                val vm = HomeViewModel(app)
                store.put("test", vm)
                vm.removeItem(id)
            }
            repo.flush()
            assertNull(repo.find(id))
            assertEquals(uri.toString(), app.database.historyDao().byId(historyId)!!.inputUri)
            assertEquals("retained-source", app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            OutputStore.delete(app, historyId)
            repo.flush()
            input.delete()
        }
    }

    @Test fun cachedDetailSuppliesThumbnailWithoutAnotherNativeDecode() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "preview-${UUID.randomUUID()}.jpg")
        try {
            instrumentation.context.assets.open("apple_gainmap_new.jpg").use { input -> source.outputStream().use { input.copyTo(it) } }
            val large = PreviewCache.load(context, source.path, 1600)
            assertNotNull(large)
            assertTrue(maxOf(large!!.width, large.height) > 128)
            val directory = File(context.cacheDir, "previews")
            val before = directory.list()!!.toSet()
            val small = PreviewCache.load(context, source.path, 112)
            assertNotNull(small)
            assertEquals(128, maxOf(small!!.width, small.height))
            assertEquals(before, directory.list()!!.toSet())
            assertSame(small, PreviewCache.load(context, source.path, 128))
        } finally { source.delete() }
    }
}

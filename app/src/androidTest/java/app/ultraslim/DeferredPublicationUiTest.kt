package app.ultraslim

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.convert.*
import app.ultraslim.data.*
import app.ultraslim.model.OutputFormat
import app.ultraslim.ui.home.HomeViewModel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class DeferredPublicationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun backgroundConversionPublishesOnlyAfterReturningToFullApp() {
        val app = compose.activity.application as UltraSlimApp
        val id = UUID.randomUUID().toString()
        val file = File(app.cacheDir, "$id.jpg")
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff115566.toInt()) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }; bitmap.recycle()
        val source = MediaStoreSaver.save(app, file, "$id.jpg", OutputFormat.JPEG)!!
        var locked = false
        try {
            runBlocking {
                app.settingsRepository.setAutoReplaceOriginals(false)
                app.conversionMutex.lock(); locked = true
                app.conversionRepository.awaitReady()
                app.conversionRepository.addAll(listOf(QueueItem(id, source.toString(), "$id.jpg", file.length(), status = QueueStatus.READY, batchId = id)))
                app.conversionRepository.flush()
                app.database.taskDao().putBatch(BatchEntity(id, "[\"$id\"]", ConversionDefaults(format = OutputFormat.JPEG, onlySmaller = false).encode()))
            }
            compose.runOnUiThread { ConversionService.start(app, id) }
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            compose.waitUntil(5000) { !app.galleryPublishingAllowed.value }
            app.conversionMutex.unlock(); locked = false
            compose.waitUntil(30_000) { !app.conversionServiceRunning.value }
            runBlocking {
                val row = app.database.historyDao().byTask(id)!!
                assertEquals("PENDING", row.saveState)
                assertTrue(File(row.outputUri).isFile)
            }
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            compose.waitUntil(15_000) { runBlocking { app.database.historyDao().byTask(id)?.saveState == "SAVED" } }
            runBlocking {
                val row = app.database.historyDao().byTask(id)!!
                assertTrue(row.outputUri.startsWith("content://media/"))
                assertEquals(row.outputUri, app.conversionRepository.find(id)?.outputUri)
            }
        } finally {
            if (locked) app.conversionMutex.unlock()
            runBlocking {
                app.database.taskDao().cancel(id)
                app.database.historyDao().byTask(id)?.let { row ->
                    if (row.outputUri.startsWith("content://")) app.contentResolver.delete(Uri.parse(row.outputUri), null, null)
                    else File(row.outputUri).delete()
                    app.database.historyDao().deleteById(row.id)
                }
                app.conversionRepository.remove(id); app.conversionRepository.flush()
            }
            app.contentResolver.delete(source, null, null); file.delete()
        }
    }

    @Test fun newBatchRunsFromEndOfQueueWithoutReorderingTheVisibleList() {
        val app = compose.activity.application as UltraSlimApp
        val ids = List(3) { UUID.randomUUID().toString() }
        val store = androidx.lifecycle.ViewModelStore()
        lateinit var vm: HomeViewModel
        var locked = false
        var batchId: String? = null
        try {
            runBlocking {
                app.conversionRepository.awaitReady()
                app.conversionMutex.lock(); locked = true
                app.conversionRepository.addAll(ids.map { QueueItem(it, "content://order/$it", "$it.jpg", 123, status = QueueStatus.READY) })
            }
            compose.runOnIdle {
                vm = HomeViewModel(app)
                store.put("order", vm)
                vm.startConversion(ids.toSet())
            }
            compose.waitUntil(5000) { app.conversionServiceRunning.value }
            runBlocking {
                val batch = app.database.taskDao().latestBatch()!!
                batchId = batch.id
                val array = org.json.JSONArray(batch.itemIds)
                assertEquals(ids.reversed(), List(array.length()) { array.getString(it) })
                assertEquals(ids, app.conversionRepository.queue.value.filter { it.id in ids }.map { it.id })
                app.database.taskDao().cancel(batch.id)
            }
        } finally {
            runBlocking { batchId?.let { app.database.taskDao().cancel(it) } }
            if (locked) app.conversionMutex.unlock()
            compose.waitUntil(10_000) { !app.conversionServiceRunning.value }
            compose.runOnIdle { store.clear() }
            runBlocking { app.conversionRepository.removeIds(ids); app.conversionRepository.flush() }
        }
    }

}

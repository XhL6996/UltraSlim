package app.ultraslim

import android.net.Uri
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.data.HistoryEntity
import app.ultraslim.ui.detail.DetailViewModel
import app.ultraslim.ui.detail.DetailUiState
import app.ultraslim.ui.detail.OriginalAccess
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class DetailRelinkTest {
    @Test fun relinkChecksOnIoAndRejectsPreviousUrisLateResult() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UltraSlimApp
        val dao = app.database.historyDao()
        val token = UUID.randomUUID().toString()
        val oldUri = "content://ultraslim-test/$token/old"
        val source = File(app.filesDir, "inputs/relink-$token.jpg").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open("apple_gainmap_new.jpg").use { input -> source.outputStream().use { input.copyTo(it) } }
        val sourceUri = FileProvider.getUriForFile(app, "${app.packageName}.files", source)
        val historyId = dao.insert(HistoryEntity(
            displayName = "relink.jpg", inputUri = oldUri, outputUri = "/missing/$token",
            inputBytes = source.length(), outputBytes = 1, width = 1, height = 1,
            gainmapWidth = 0, gainmapHeight = 0, hadGainMap = false,
            format = "JPEG", inputFormat = "JPEG", durationMs = 1.0, maxContentBoost = 1f,
            createdAt = System.currentTimeMillis(), status = "DONE", saveState = "PENDING",
        ))
        val oldEntered = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val newEntered = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)
        val queriedOnMain = AtomicBoolean(false)
        val newChecks = AtomicInteger(0)
        val store = ViewModelStore()
        val observed = Collections.synchronizedList(mutableListOf<DetailUiState>())
        val vm = withContext(Dispatchers.Main) {
            DetailViewModel(app, historyId, queryOriginal = { uri ->
                if (Looper.myLooper() == Looper.getMainLooper()) queriedOnMain.set(true)
                if (uri.toString() == oldUri) {
                    oldEntered.countDown()
                    check(releaseOld.await(15, TimeUnit.SECONDS))
                    OriginalAccess(trashed = true, canTrash = false)
                } else {
                    newChecks.incrementAndGet()
                    newEntered.countDown()
                    check(releaseNew.await(15, TimeUnit.SECONDS))
                    OriginalAccess(trashed = false, canTrash = true)
                }
            }).also { store.put("detail", it) }
        }
        val collector = launch(Dispatchers.Default) { vm.uiState.collect { observed += it } }
        try {
            withTimeout(5_000) { vm.uiState.first { it.entity?.inputUri == oldUri } }
            assertTrue(oldEntered.await(5, TimeUnit.SECONDS))
            withContext(Dispatchers.Main) { vm.relinkSource(sourceUri) }
            val linked = withTimeout(5_000) {
                vm.uiState.first { it.entity != null && it.entity.inputUri != oldUri && !it.saving }
            }
            // Relinking completes even while the old blocking provider call is outstanding.
            assertFalse(linked.canTrashOriginal)
            assertFalse(linked.originalTrashed)
            releaseOld.countDown()
            assertTrue(newEntered.await(5, TimeUnit.SECONDS))
            assertFalse(vm.uiState.value.originalTrashed)
            assertFalse(vm.uiState.value.canTrashOriginal)
            releaseNew.countDown()
            withTimeout(5_000) { vm.uiState.first { it.entity?.inputUri == linked.entity!!.inputUri && it.canTrashOriginal } }
            assertFalse(queriedOnMain.get())
            assertEquals(1, newChecks.get())
            assertFalse(synchronized(observed) {
                observed.any { it.entity?.inputUri == linked.entity!!.inputUri && it.originalTrashed }
            })
        } finally {
            releaseOld.countDown()
            releaseNew.countDown()
            collector.cancelAndJoin()
            withContext(Dispatchers.Main) { store.clear() }
            dao.byId(historyId)?.inputUri?.takeIf { it != oldUri }?.let { current ->
                val id = Uri.parse(current).lastPathSegment
                if (id != null) {
                    File(app.filesDir, "inputs/$id").delete()
                    File(app.filesDir, "input-metadata/$id.json").delete()
                }
            }
            dao.deleteById(historyId)
            source.delete()
        }
    }
}

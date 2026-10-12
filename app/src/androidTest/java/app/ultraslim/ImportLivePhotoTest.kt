package app.ultraslim

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.convert.MotionPhotoContainer
import app.ultraslim.convert.MotionPhotoInfo
import app.ultraslim.ui.home.HomeEvent
import app.ultraslim.ui.home.HomeViewModel
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ImportLivePhotoTest {
    @Test fun filterUsesContentAndReportsSkippedPhotosBeforeCopying() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UltraSlimApp
        val repo = app.conversionRepository
        repo.awaitReady()
        val originalIds = repo.queue.value.map { it.id }.toSet()
        val inputDir = File(app.filesDir, "inputs").apply { mkdirs() }
        val originalFiles = inputDir.listFiles().orEmpty().toSet()
        val dir = File(app.cacheDir, "outputs/import-live-test").apply { mkdirs() }
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) { HomeViewModel(app).also { store.put("test", it) } }
        fun uri(file: File) = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        suspend fun import(uris: List<Uri>, ignore: Boolean): String = coroutineScope {
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(30_000) {
                    vm.events.first { it is HomeEvent.Info && it.message.startsWith("已导入") } as HomeEvent.Info
                }
            }
            withContext(Dispatchers.Main) { vm.addItems(uris, ignore) }
            result.await().message
        }
        try {
            val still = instrumentation.context.assets.open("apple_gainmap_new.jpg").use { it.readBytes() }
            fun box(type: String, payload: ByteArray) = ByteBuffer.allocate(payload.size + 8)
                .putInt(payload.size + 8).put(type.toByteArray()).put(payload).array()
            val video = box("ftyp", "isom0000".toByteArray()) + box("moov", byteArrayOf()) + box("mdat", ByteArray(1024))
            val motion = File(dir, "ordinary-name.jpg").apply {
                writeBytes(MotionPhotoContainer.jpegWithXmp(still, MotionPhotoInfo(0, video.size.toLong(), 0)) + video)
            }
            val static = File(dir, "MVIMG_MotionPhoto_still.jpg").apply { writeBytes(still) }

            // All-live selections must report why nothing was added and leave no private copies.
            assertEquals("已导入 0 张照片；已忽略 1 张实况照片", import(listOf(uri(motion)), true))
            assertEquals(originalIds, repo.queue.value.map { it.id }.toSet())
            assertEquals(originalFiles, inputDir.listFiles().orEmpty().toSet())

            // Misleading filenames do not affect detection; a mixed selection keeps only the still.
            assertEquals("已导入 1 张照片；已忽略 1 张实况照片", import(listOf(uri(motion), uri(static)), true))
            val added = repo.queue.value.filter { it.id !in originalIds }
            assertEquals(listOf(static.name), added.map { it.displayName })
            assertTrue(added.single().probe!!.hasGainMap)

            // Turning the filter off restores the normal import behavior.
            assertEquals("已导入 1 张照片", import(listOf(uri(motion)), false))
            assertEquals(2, repo.queue.value.count { it.id !in originalIds })
            assertArrayEquals(still, static.readBytes())
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            repo.removeIds(repo.queue.value.filter { it.id !in originalIds }.map { it.id })
            repo.flush()
            inputDir.listFiles().orEmpty().filter { it !in originalFiles }.forEach { it.delete() }
            dir.deleteRecursively()
        }
    }
}

package app.ultraslim.convert

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.ultraslim.data.HistoryEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object AppleLiveExporter {
    suspend fun export(context: Context, record: HistoryEntity, destination: Uri) = withContext(Dispatchers.IO) {
        require(record.format.equals("HEIC", ignoreCase = true)) { "请先将动态照片转换为 HEIC" }
        val source = OutputStore.uri(context, record.outputUri)
        val info = MotionPhotoHelper.detect(context, source) ?: error("此成品没有动态视频，请用原始动态照片重新转换为 HEIC")
        val temp = File.createTempFile("apple-live-", ".zip", context.cacheDir)
        val task = currentCoroutineContext()
        try {
            val fd = context.contentResolver.openFileDescriptor(source, "r") ?: error("无法读取成品")
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                temp.outputStream().use { AppleLiveBundle.write(input.channel, info, it) { task.ensureActive() } }
            }
            task.ensureActive()
            val output = context.contentResolver.openOutputStream(destination, "wt") ?: error("无法写入导出文件")
            try {
                output.use { out -> temp.inputStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) { task.ensureActive(); val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
                } }
            } catch (e: Exception) {
                // Remove our incomplete SAF document; never touch the original/output photo.
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, destination) }
                throw e
            }
        } finally { temp.delete() }
    }
}

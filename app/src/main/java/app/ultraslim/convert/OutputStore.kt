package app.ultraslim.convert

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.ultraslim.UltraSlimApp
import app.ultraslim.data.HistoryEntity
import app.ultraslim.model.OutputFormat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object OutputStore {
    private val saveLock = Mutex()
    suspend fun save(app: UltraSlimApp, record: HistoryEntity): HistoryEntity = saveLock.withLock {
        val current = app.database.historyDao().byId(record.id) ?: error("记录已删除")
        if (current.outputUri.startsWith("content://")) return@withLock current
        val replacement = app.database.replacementDao().byId(current.id)?.takeIf { it.state == "PREPARED" }
        val source = Uri.parse(replacement?.sourceUri ?: current.inputUri)
        val metadata = withContext(Dispatchers.IO) { SourceMedia.read(app, source) }
        val uri = try { withContext(Dispatchers.IO) {
            val file = File(current.outputUri)
            val format = OutputFormat.parse(current.format)
            val task = current.taskId ?: "history-${current.id}"
            val date = metadata?.dateTaken ?: SourceMedia.captureTime(app, source)
            val sameAlbum = if (replacement != null && metadata != null) MediaStoreSaver.save(app, file,
                current.displayName, format, taskId = task, relativePath = metadata.relativePath,
                volume = metadata.volume, dateTaken = date, relatedUri = source) else null
            if (sameAlbum != null) sameAlbum else {
                if (replacement != null) AutoReplacement.finish(app, listOf(replacement), "SKIPPED",
                    "无法保存回原相册，成品保存到 UltraSlim，原图保留")
                MediaStoreSaver.save(app, file, current.displayName, format, taskId = task, dateTaken = date)
            }
        } } catch(e: kotlinx.coroutines.CancellationException) { throw e } catch(e: Exception) { null }
        // finish() may have added a replacement outcome while saving.
        val latest = app.database.historyDao().byId(current.id) ?: current
        val updated = if (uri != null) latest.copy(outputUri = uri.toString(), saveState = "SAVED") else latest.copy(saveState = "FAILED")
        app.database.historyDao().update(updated)
        if (uri != null) withContext(Dispatchers.IO) { File(current.outputUri).delete() }
        updated
    }
    suspend fun delete(app: UltraSlimApp, id: Long) = saveLock.withLock {
        val row = app.database.historyDao().byId(id) ?: return@withLock
        app.database.replacementDao().delete(id)
        app.database.historyDao().deleteById(id)
        if (!row.outputUri.startsWith("content://")) withContext(Dispatchers.IO) { File(row.outputUri).delete() }
        row.taskId?.let { app.conversionRepository.remove(it) }
    }

    /** 批量删除：一次 saveLock、批量 SQL、队列清理只做一次 persist。 */
    suspend fun deleteBatch(app: UltraSlimApp, ids: List<Long>) = saveLock.withLock {
        if (ids.isEmpty()) return@withLock
        val rows = ids.chunked(500).flatMap { app.database.historyDao().byIds(it) }
        app.database.replacementDao().deleteAll(ids)
        app.database.historyDao().deleteByIds(ids)
        withContext(Dispatchers.IO) {
            rows.forEach { row ->
                if (!row.outputUri.startsWith("content://")) File(row.outputUri).delete()
            }
        }
        app.conversionRepository.removeIds(rows.mapNotNull { it.taskId })
    }
    fun uri(context: Context, value: String, displayName: String? = null): Uri = if (value.startsWith("content://")) Uri.parse(value)
        else FileProvider.getUriForFile(context, "${context.packageName}.files", File(value), displayName ?: File(value).name)

    fun share(context: Context, records: List<HistoryEntity>): Boolean = runCatching {
        val uris = ArrayList(records.map { uri(context, it.outputUri, it.displayName) })
        require(uris.isNotEmpty())
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = records.map { OutputFormat.parse(it.format).mimeType }.distinct().singleOrNull() ?: "image/*"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = android.content.ClipData.newUri(context.contentResolver, "图片", uris.first()).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享图片"))
        true
    }.getOrDefault(false)
}

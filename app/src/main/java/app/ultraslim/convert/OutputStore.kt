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
        val uri = try { withContext(Dispatchers.IO) {
            MediaStoreSaver.save(app, File(current.outputUri), current.displayName, OutputFormat.parse(current.format), taskId = current.taskId ?: "history-${current.id}")
        } } catch(e: kotlinx.coroutines.CancellationException) { throw e } catch(e: Exception) { null }
        val updated = if (uri != null) current.copy(outputUri = uri.toString(), saveState = "SAVED") else current.copy(saveState = "FAILED")
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

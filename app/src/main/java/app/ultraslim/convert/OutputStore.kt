package app.ultraslim.convert

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import app.ultraslim.UltraSlimApp
import app.ultraslim.data.HistoryEntity
import app.ultraslim.data.decodeDefaults
import app.ultraslim.model.OutputFormat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object OutputStore {
    private val saveLock = Mutex()
    suspend fun save(
        app: UltraSlimApp,
        record: HistoryEntity,
        expectedFingerprint: MediaStoreSaver.Fingerprint? = null,
    ): HistoryEntity = saveLock.withLock {
        val current = app.database.historyDao().byId(record.id) ?: error("记录已删除")
        if (current.outputUri.startsWith("content://")) return@withLock current
        val replacement = app.database.replacementDao().byId(current.id)?.takeIf { it.state == "PREPARED" }
        // Reuse the fingerprint computed by the runner; each destination verifies the published copy.
        val expected = expectedFingerprint ?: withContext(Dispatchers.IO) {
            MediaStoreSaver.fingerprintOf(File(current.outputUri))
        }
        var saveError: String? = null
        val uri = try {
            // Use the batch snapshot, not today's settings, including manual/deferred retries.
            val options = app.database.taskDao().batch(current.batchId)?.let { decodeDefaults(it.settings) }
            withContext(Dispatchers.IO) {
                val file = File(current.outputUri)
                val format = OutputFormat.parse(current.format)
                val directory = options?.effectiveOutputDirectoryUri.orEmpty()
                val source = Uri.parse(replacement?.sourceUri ?: current.inputUri)
                val timestamps = OutputMetadataStore.read(file) ?: SourceMedia.timestamps(app, source)
                val modifiedTime = timestamps.outputModifiedAt
                if (directory.isNotBlank()) {
                    return@withContext FolderOutput.save(app, file, Uri.parse(directory),
                        current.displayName, format.mimeType, expected ?: error("无法读取成品"),
                        keepFilename = options?.keepFilename == true, modifiedTime = modifiedTime)
                }
                val metadata = SourceMedia.read(app, source)
                val task = current.taskId ?: "history-${current.id}"
                val date = timestamps.dateTaken
                val sameAlbum = if (replacement != null && metadata != null) MediaStoreSaver.save(app, file,
                    current.displayName, format, taskId = task, relativePath = metadata.relativePath,
                    volume = metadata.volume, dateTaken = date, relatedUri = source,
                    expectedFingerprint = expected, keepFilename = options?.keepFilename == true,
                    fileModifiedTime = modifiedTime, onFailure = { saveError = it.message }) else null
                if (sameAlbum != null) sameAlbum else {
                    if (replacement != null) AutoReplacement.finish(app, listOf(replacement), "SKIPPED",
                        "无法保存回原相册，成品保存到 UltraSlim，原图保留")
                    MediaStoreSaver.save(app, file, current.displayName, format, taskId = task, dateTaken = date,
                        expectedFingerprint = expected, keepFilename = options?.keepFilename == true,
                        fileModifiedTime = modifiedTime, onFailure = { saveError = it.message })
                }
            }
        } catch(e: kotlinx.coroutines.CancellationException) { throw e } catch(e: Exception) {
            android.util.Log.w("UltraSlimOutput", "Output publication failed", e)
            saveError = e.message ?: "输出文件保存失败"
            null
        }
        // finish() may have added a replacement outcome while saving.
        // MediaStoreSaver.save only returns a non-null URI after matches(resolver, uri, expected) succeeds.
        val verified = uri?.takeIf { expected != null && expected.bytes > 0 }
        val latest = app.database.historyDao().byId(current.id) ?: current
        val actualName = verified?.let { withContext(Dispatchers.IO) {
            runCatching { MediaStoreSaver.displayName(app, it) }.getOrNull()
        } }
        val updated = if (verified != null) latest.copy(outputUri = verified.toString(),
            displayName = actualName ?: latest.displayName, saveState = "SAVED") else latest.copy(saveState = "FAILED")
        withContext(Dispatchers.IO) { OutputSaveError.write(File(current.outputUri),
            if (verified != null) null else saveError ?: "输出文件保存或校验失败，请检查目录授权") }
        app.database.historyDao().update(updated)
        if (verified != null) withContext(Dispatchers.IO) {
            File(current.outputUri).delete()
            PublicationReceipt.delete(File(current.outputUri))
            OutputMetadataStore.delete(File(current.outputUri))
        }
        updated
    }
    class ConversionInProgress : IllegalStateException("请等待当前转换结束后删除记录")

    suspend fun delete(app: UltraSlimApp, id: Long) = deleteBatch(app, listOf(id))

    /** 与整个转换批次互斥；saveLock 仍负责与手动/延迟发布互斥。 */
    suspend fun deleteBatch(app: UltraSlimApp, ids: List<Long>) {
        if (ids.isEmpty()) return
        if (!app.conversionMutex.tryLock()) throw ConversionInProgress()
        try {
            // 包含已经排队、尚未取得 conversionMutex 的服务或旧 Worker。
            if (app.conversionRunning.first()) throw ConversionInProgress()
            saveLock.withLock {
                app.conversionRepository.awaitReady()
                val rows = ids.chunked(500).flatMap { app.database.historyDao().byIds(it) }
                // 取消只发生在批次之间，不能留下已删历史但仍引用私有成品的队列。
                withContext(kotlinx.coroutines.NonCancellable) {
                    app.database.withTransaction {
                        app.database.replacementDao().deleteAll(ids)
                        app.database.historyDao().deleteByIds(ids)
                    }
                    withContext(Dispatchers.IO) {
                        rows.forEach { row ->
                            if (!row.outputUri.startsWith("content://")) {
                                File(row.outputUri).delete()
                                PublicationReceipt.delete(File(row.outputUri))
                                OutputSaveError.write(File(row.outputUri), null)
                                OutputMetadataStore.delete(File(row.outputUri))
                            }
                        }
                    }
                    app.conversionRepository.removeIds(rows.mapNotNull { it.taskId })
                    app.conversionRepository.flush()
                }
            }
        } finally {
            app.conversionMutex.unlock()
        }
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

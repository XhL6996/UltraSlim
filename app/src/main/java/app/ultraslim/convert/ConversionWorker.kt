package app.ultraslim.convert

import app.ultraslim.util.outputName
import android.Manifest
import android.app.PendingIntent
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import app.ultraslim.R
import app.ultraslim.UltraSlimApp
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.data.*
import app.ultraslim.util.formatBytes
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray

/** Durable, sequential batches. Each result is checkpointed before publishing to the album. */
class ConversionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val UNIQUE_WORK_NAME = "conversion"
        const val CHANNEL_ID = "conversion"
        private const val NOTIFICATION_ID = 42
        private const val SUMMARY_ID = 43
        const val KEY_BATCH = "batch_id"
    }
    override suspend fun getForegroundInfo() = createForegroundInfo(0, "正在准备转换…", null, true)

    override suspend fun doWork(): Result {
        val app = applicationContext as UltraSlimApp
        val repo = app.conversionRepository
        repo.awaitReady()
        val batchId = inputData.getString(KEY_BATCH) ?: return Result.failure()
        val batch = app.database.taskDao().batch(batchId) ?: return Result.failure()
        val options = decodeDefaults(batch.settings)
        val json = JSONArray(batch.itemIds)
        val ids = List(json.length()) { json.getString(it) }
        if (batch.cancelled) return Result.success()
        try { setForeground(getForegroundInfo()) } catch (e: Exception) {
            if (e is CancellationException) throw e
            ids.forEach { id -> repo.update(id) { it.copy(status = QueueStatus.FAILED, error = "后台任务启动失败，请回到应用重试：${e.message}") } }
            repo.flush()
            return Result.failure()
        }
        var done = 0; var saved = 0L
        val started = System.currentTimeMillis()
        try {
            for ((index, id) in ids.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (app.database.taskDao().batch(batchId)?.cancelled != false) break
                if (Build.VERSION.SDK_INT >= 29 && app.getSystemService(android.os.PowerManager::class.java).currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE) {
                    repo.update(id) { it.copy(error = "设备温度较高，稍后自动重试") }; repo.flush(); return Result.retry()
                }
                val item = repo.find(id) ?: continue // Removed pending items never run.
                if (item.status == QueueStatus.DONE || item.status == QueueStatus.SKIPPED) continue
                val elapsedSec = (System.currentTimeMillis() - started) / 1000.0
                val speedText = if (done > 0 && elapsedSec > 0.5) {
                    val rate = done / elapsedSec
                    val remaining = ids.size - index
                    val etaSec = (remaining / rate).toInt()
                    String.format(java.util.Locale.ROOT, " · %.1f 张/秒 (约 %d 秒)", rate, etaSec)
                } else ""
                val detailText = if (saved > 0) "已省 ${formatBytes(saved)}$speedText" else "当前图片完成后可停止$speedText"
                setForeground(createForegroundInfo(index * 100 / ids.size.coerceAtLeast(1), "正在转换 ${index + 1}/${ids.size}", detailText, false))
                if (repo.claim(id, batchId) == null) continue
                repo.flush()
                try {
                    var existing = app.database.historyDao().byTask(id)
                    if (existing != null) {
                        if (options.autoSave && app.database.taskDao().batch(batchId)?.cancelled == false) existing = OutputStore.save(app, existing)
                        val recovered = existing
                        repo.update(id) { it.copy(status = QueueStatus.DONE, outputUri = recovered.outputUri, historyId = recovered.id,
                            error = if(recovered.saveState == "SAVED") null else "转换完成，待保存；可在详情重试") }
                        continue
                    }
                    val source = Uri.parse(item.uri)
                    val probe = withContext(Dispatchers.IO) {
                        app.contentResolver.openFileDescriptor(source, "r")?.use { UltraHdrBridge.nativeProbe(it.fd) }
                    } ?: error("无法读取原图，请重新选择或授权")
                    check(probe.isSuccess) { probe.error }
                    repo.update(id) { it.copy(probe = probe) }
                    if (!probe.hasGainMap && !options.allowSdr) {
                        repo.update(id) { it.copy(status = QueueStatus.SKIPPED, error = "按设置跳过 SDR 图片") }; continue
                    }
                    val mem = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                    val pixels = probe.width.toLong() * probe.height
                    check(pixels > 0 && pixels * (if (probe.hasGainMap) 40 else 16) < mem.availMem * 3 / 5) { "当前可用内存不足，请关闭其他应用后重试" }
                    val file = File(app.filesDir, "outputs/$id.${options.format.extension}").apply { parentFile?.mkdirs() }
                    suspend fun transcode(quality: Int) = withContext(Dispatchers.IO) {
                        app.contentResolver.openFileDescriptor(source, "r")!!.use { input ->
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { output ->
                                UltraHdrBridge.nativeTranscode(input.fd, output.fd, options.format.codecId, quality,
                                    options.gainmapQuality, options.gainmapScale, options.multiChannelGainmap, options.keepExif,
                                    options.allowSdr, options.speedLevel, options.resizePercent,
                                    Runtime.getRuntime().availableProcessors().coerceIn(2, 8), options.keepLocation)
                            }
                        }
                    }
                    // "仅保留更小"开启时自动降档重试：结果不比原图小就换更低画质再压一次
                    // （最多两档），仍压不过才保留原图——保证交给用户的结果只会更小。
                    var quality = options.quality
                    var result = transcode(quality)
                    if (!result.isSuccess) { file.delete(); error(result.error) }
                    var steps = 0
                    while (options.onlySmaller && result.outputBytes >= result.inputBytes && steps < 2 && quality > 50) {
                        quality = maxOf(50, quality - 14)
                        steps++
                        result = transcode(quality)
                        if (!result.isSuccess) { file.delete(); error(result.error) }
                    }
                    val verified = withContext(Dispatchers.IO) {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                            UltraHdrBridge.nativeProbe(it.fd)
                        }
                    }
                    if (!verified.isSuccess || (probe.hasGainMap && !verified.hasGainMap) || (options.keepExif && probe.hasExif && !verified.hasExif)) { file.delete(); error("输出校验失败") }
                    if (options.onlySmaller && result.outputBytes >= result.inputBytes) {
                        file.delete()
                        val why = if (steps > 0) "已尽力压缩（降 $steps 档画质）仍无法比原图小，已保留原图" else "输出没有比原图小，已保留原图"
                        repo.update(id) { it.copy(status = QueueStatus.SKIPPED, result = result, error = why) }
                        continue
                    }
                    val name = outputName(item.displayName, options.format.extension, id, item.createdAt)
                    // Commit the durable private output first. Saving can be retried without encoding again.
                    var entity = HistoryEntity(displayName = name, inputUri = item.uri, outputUri = file.path,
                        inputBytes = result.inputBytes, outputBytes = result.outputBytes, width = verified.width, height = verified.height,
                        gainmapWidth = verified.gainmapWidth, gainmapHeight = verified.gainmapHeight, hadGainMap = verified.hasGainMap,
                        format = options.format.label, inputFormat = probe.containerLabel, durationMs = result.durationMs,
                        maxContentBoost = verified.maxContentBoost, createdAt = System.currentTimeMillis(), status = HistoryEntity.STATUS_DONE,
                        taskId = id, batchId = batchId, saveState = "PENDING", settingsSummary = options.summary(), hasExif = verified.hasExif)
                    val historyId = withContext(NonCancellable) { app.database.historyDao().insert(entity) }
                    entity = entity.copy(id = historyId)
                    if (options.autoSave && app.database.taskDao().batch(batchId)?.cancelled == false) {
                        entity = OutputStore.save(app, entity)
                    }
                    repo.update(id) { it.copy(status = QueueStatus.DONE, progress = 100, result = result,
                        outputUri = entity.outputUri, historyId = historyId,
                        error = if (entity.saveState == "FAILED") "转换完成，保存失败；可在详情重试保存" else if (entity.saveState != "SAVED") "待保存，可直接分享" else null) }
                    done++; saved += result.inputBytes - result.outputBytes
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    repo.update(id) { it.copy(status = QueueStatus.FAILED, error = e.message ?: "转换失败") }
                } finally { withContext(NonCancellable) { repo.flush() } }
            }
        } finally {
            ids.forEach { id -> repo.update(id) { if (it.status == QueueStatus.CONVERTING) it.copy(status = QueueStatus.READY, error = "已中断，可继续") else it } }
            withContext(NonCancellable) { repo.flush() }
        }
        if (app.database.taskDao().batch(batchId)?.cancelled == false) showSummary(done, saved)
        return Result.success()
    }

    private fun createForegroundInfo(
        progress: Int,
        title: String,
        text: String?,
        indeterminate: Boolean,
    ): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** Best-effort completion notification, shown only when notifications are allowed. */
    private fun showSummary(doneCount: Int, savedBytes: Long) {
        if (doneCount <= 0) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("转换完成")
            .setContentText("完成 $doneCount 张，输出体积净变化 ${app.ultraslim.util.formatByteDelta(-savedBytes)}（原图保留）")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(SUMMARY_ID, notification) }
    }
}

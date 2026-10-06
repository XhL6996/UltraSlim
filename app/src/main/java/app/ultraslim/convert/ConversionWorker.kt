package app.ultraslim.convert

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.ultraslim.R
import app.ultraslim.UltraSlimApp
import app.ultraslim.bridge.ProbeResult
import app.ultraslim.bridge.TranscodeResult
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.data.ConversionDefaults
import app.ultraslim.data.HistoryDao
import app.ultraslim.data.HistoryEntity
import app.ultraslim.model.OutputFormat
import app.ultraslim.util.formatBytes
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Converts the queued images on background threads, publishing progress through
 * a foreground-service notification and through the shared [ConversionRepository].
 * Successful conversions are recorded in Room.
 *
 * Up to [MAX_PARALLEL] items are transcoded concurrently; overall progress is
 * tracked as completed/total. When the work is cancelled (WorkManager stop),
 * items still in CONVERTING are reset to READY/PENDING and the worker finishes
 * quietly without a summary notification.
 */
class ConversionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "conversion"
        const val CHANNEL_ID = "conversion"

        private const val MAX_PARALLEL = 2

        private const val NOTIFICATION_ID = 42
        private const val SUMMARY_ID = 43

        private const val KEY_URIS = "uris"
        private const val KEY_FORMAT = "format"
        private const val KEY_QUALITY = "quality"
        private const val KEY_GAINMAP_QUALITY = "gainmap_quality"
        private const val KEY_GAINMAP_SCALE = "gainmap_scale"
        private const val KEY_MULTI_CHANNEL = "multi_channel_gainmap"
        private const val KEY_KEEP_EXIF = "keep_exif"
        private const val KEY_ALLOW_SDR = "allow_sdr"
        private const val KEY_AUTO_SAVE = "auto_save"
        private const val KEY_SPEED_LEVEL = "speed_level"
        private const val KEY_RESIZE_PERCENT = "resize_percent"

        fun buildInputData(uris: List<String>, defaults: ConversionDefaults): Data =
            Data.Builder()
                .putStringArray(KEY_URIS, uris.toTypedArray())
                .putString(KEY_FORMAT, defaults.format.extension)
                .putInt(KEY_QUALITY, defaults.quality)
                .putInt(KEY_GAINMAP_QUALITY, defaults.gainmapQuality)
                .putInt(KEY_GAINMAP_SCALE, defaults.gainmapScale)
                .putBoolean(KEY_MULTI_CHANNEL, defaults.multiChannelGainmap)
                .putBoolean(KEY_KEEP_EXIF, defaults.keepExif)
                .putBoolean(KEY_ALLOW_SDR, defaults.allowSdr)
                .putBoolean(KEY_AUTO_SAVE, defaults.autoSave)
                .putInt(KEY_SPEED_LEVEL, defaults.speedLevel)
                .putInt(KEY_RESIZE_PERCENT, defaults.resizePercent)
                .build()
    }

    /** Immutable options shared by every item of one run. */
    private data class RunOptions(
        val format: OutputFormat,
        val quality: Int,
        val gainmapQuality: Int,
        val gainmapScale: Int,
        val multiChannel: Boolean,
        val keepExif: Boolean,
        val allowSdr: Boolean,
        val autoSave: Boolean,
        val speedLevel: Int,
        val resizePercent: Int,
    )

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo(0, "正在准备转换…", null, indeterminate = true)

    override suspend fun doWork(): Result {
        val app = applicationContext as UltraSlimApp
        val repo = app.conversionRepository
        val dao = app.database.historyDao()

        val opts = RunOptions(
            format = OutputFormat.parse(inputData.getString(KEY_FORMAT)),
            quality = inputData.getInt(KEY_QUALITY, 90),
            gainmapQuality = inputData.getInt(KEY_GAINMAP_QUALITY, 60),
            gainmapScale = inputData.getInt(KEY_GAINMAP_SCALE, 4),
            multiChannel = inputData.getBoolean(KEY_MULTI_CHANNEL, true),
            keepExif = inputData.getBoolean(KEY_KEEP_EXIF, true),
            allowSdr = inputData.getBoolean(KEY_ALLOW_SDR, true),
            autoSave = inputData.getBoolean(KEY_AUTO_SAVE, true),
            speedLevel = inputData.getInt(KEY_SPEED_LEVEL, 1),
            resizePercent = inputData.getInt(KEY_RESIZE_PERCENT, 100),
        )

        val uris = inputData.getStringArray(KEY_URIS)?.toList().orEmpty()
        if (uris.isEmpty()) return Result.success()

        runCatching { setForeground(getForegroundInfo()) }

        val total = uris.size
        val completed = AtomicInteger(0)
        val doneCount = AtomicInteger(0)
        val savedBytes = AtomicLong(0)
        val semaphore = Semaphore(MAX_PARALLEL)

        try {
            coroutineScope {
                uris.map { uriString ->
                    async {
                        semaphore.withPermit {
                            val (success, saved) = processItem(repo, dao, uriString, opts) {
                                completed.get() * 100 / total
                            }
                            val done = completed.incrementAndGet()
                            if (success) {
                                doneCount.incrementAndGet()
                                savedBytes.addAndGet(saved)
                            }
                            runCatching {
                                setForeground(
                                    createForegroundInfo(
                                        progress = done * 100 / total,
                                        title = "正在转换 $done/$total",
                                        text = null,
                                        indeterminate = false,
                                    ),
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
        } catch (ce: CancellationException) {
            // WorkManager stopped us (e.g. user pressed 取消): leave the queue in
            // a re-runnable state and finish quietly, without a summary.
            resetInterruptedItems(repo, uris)
            return Result.success()
        }

        showSummary(doneCount.get(), savedBytes.get())
        return Result.success()
    }

    /**
     * Probe → transcode → persist one image. Returns (success, savedBytes).
     * Per-item repository state is updated as it goes. CancellationException is
     * rethrown so the whole run unwinds; other failures mark the item FAILED.
     */
    private suspend fun CoroutineScope.processItem(
        repo: ConversionRepository,
        dao: HistoryDao,
        uriString: String,
        opts: RunOptions,
        currentProgress: () -> Int,
    ): Pair<Boolean, Long> {
        ensureActive()
        val uri = Uri.parse(uriString)
        val displayName = repo.findByUri(uriString)?.displayName ?: resolveDisplayName(uri)
        repo.updateByUri(uriString) {
            it.copy(status = QueueStatus.CONVERTING, progress = currentProgress(), error = null)
        }

        try {
            // 1. Probe: fresh fd so the worker does not depend on UI-side state.
            val probe = probe(uri)
            ensureActive()
            if (probe == null) {
                markFailed(repo, uriString, "无法读取此文件")
                return false to 0L
            }
            if (!probe.isSuccess) {
                markFailed(repo, uriString, probe.error.ifBlank { "无法解析图片" })
                return false to 0L
            }
            repo.updateByUri(uriString) { it.copy(probe = probe) }

            if (!probe.hasGainMap && !opts.allowSdr) {
                repo.updateByUri(uriString) {
                    it.copy(status = QueueStatus.SKIPPED, error = "不含 Gain Map，已按设置跳过")
                }
                return false to 0L
            }

            // 2. Transcode into a cache file via fd pair.
            val outDir = File(applicationContext.cacheDir, "outputs").apply { mkdirs() }
            val outName = outputDisplayName(displayName, opts.format)
            val safeName = outName.replace(Regex("[^A-Za-z0-9._\\-一-龥]"), "_")
            val outFile = File(outDir, "${System.currentTimeMillis()}_${Thread.currentThread().id}_$safeName")

            val result = transcode(uri, outFile, opts)
            ensureActive()
            if (result == null) {
                outFile.delete()
                markFailed(repo, uriString, "无法读取此文件")
                return false to 0L
            }
            if (!result.isSuccess) {
                outFile.delete()
                markFailed(repo, uriString, result.error.ifBlank { "转码失败" })
                return false to 0L
            }

            // 3. Persist: optionally MediaStore, always Room. Cache file is kept
            //    as the queue-item preview source.
            val savedUri = if (opts.autoSave) {
                withContext(Dispatchers.IO) {
                    MediaStoreSaver.save(applicationContext, outFile, outName, opts.format)
                }
            } else {
                null
            }
            val outputUriString = savedUri?.toString() ?: outFile.absolutePath
            val inputBytes = if (result.inputBytes > 0) {
                result.inputBytes
            } else {
                repo.findByUri(uriString)?.sizeBytes ?: 0L
            }

            val historyId = dao.insert(
                HistoryEntity(
                    displayName = outName,
                    inputUri = uriString,
                    outputUri = outputUriString,
                    inputBytes = inputBytes,
                    outputBytes = result.outputBytes,
                    width = result.width,
                    height = result.height,
                    gainmapWidth = result.gainmapWidth,
                    gainmapHeight = result.gainmapHeight,
                    hadGainMap = result.inputHadGainMap,
                    format = opts.format.label,
                    inputFormat = probe.containerLabel,
                    durationMs = result.durationMs,
                    maxContentBoost = probe.maxContentBoost,
                    createdAt = System.currentTimeMillis(),
                    status = HistoryEntity.STATUS_DONE,
                ),
            )

            repo.updateByUri(uriString) {
                it.copy(
                    status = QueueStatus.DONE,
                    progress = 100,
                    result = result,
                    outputUri = outputUriString,
                    historyId = historyId,
                    error = null,
                )
            }
            return true to (inputBytes - result.outputBytes).coerceAtLeast(0L)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            markFailed(repo, uriString, e.message ?: "转换失败")
            return false to 0L
        }
    }

    /** After cancellation, items stuck in CONVERTING become re-runnable. */
    private fun resetInterruptedItems(repo: ConversionRepository, uris: List<String>) {
        val runUris = uris.toSet()
        repo.queue.value
            .filter { it.uri in runUris && it.status == QueueStatus.CONVERTING }
            .forEach { item ->
                repo.update(item.id) {
                    it.copy(
                        status = if (it.probe != null) QueueStatus.READY else QueueStatus.PENDING,
                        progress = 0,
                        error = null,
                    )
                }
            }
    }

    private suspend fun probe(uri: Uri): ProbeResult? = withContext(Dispatchers.IO) {
        runCatching {
            applicationContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                UltraHdrBridge.nativeProbe(pfd.fd)
            }
        }.getOrNull()
    }

    private suspend fun transcode(
        uri: Uri,
        outFile: File,
        opts: RunOptions,
    ): TranscodeResult? = withContext(Dispatchers.IO) {
        runCatching {
            applicationContext.contentResolver.openFileDescriptor(uri, "r")?.use { inPfd ->
                ParcelFileDescriptor.open(
                    outFile,
                    ParcelFileDescriptor.MODE_READ_WRITE or
                        ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE,
                ).use { outPfd ->
                    UltraHdrBridge.nativeTranscode(
                        inPfd.fd,
                        outPfd.fd,
                        opts.format.codecId,
                        opts.quality,
                        opts.gainmapQuality,
                        opts.gainmapScale,
                        opts.multiChannel,
                        opts.keepExif,
                        opts.allowSdr,
                        opts.speedLevel,
                        opts.resizePercent,
                    )
                }
            }
        }.getOrNull()
    }

    private fun markFailed(repo: ConversionRepository, uriString: String, error: String) {
        repo.updateByUri(uriString) {
            it.copy(status = QueueStatus.FAILED, error = error)
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        var name: String? = null
        runCatching {
            applicationContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index)
            }
        }
        return name?.takeIf { it.isNotBlank() } ?: "未命名图片"
    }

    private fun outputDisplayName(source: String, format: OutputFormat): String {
        val base = source.substringBeforeLast('.', source).ifBlank { "ultraslim" }
        return "$base.${format.extension}"
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
            .setContentText("完成 $doneCount 张，共节省 ${formatBytes(savedBytes)}")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(SUMMARY_ID, notification) }
    }
}

package app.ultraslim.ui.home

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.ultraslim.UltraSlimApp
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.convert.ConversionRepository
import app.ultraslim.convert.ConversionWorker
import app.ultraslim.convert.QueueItem
import app.ultraslim.convert.QueueStatus
import app.ultraslim.data.ConversionDefaults
import app.ultraslim.model.OutputFormat
import app.ultraslim.ui.components.CompressionPreset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface HomeEvent {
    data class Finished(val done: Int, val failed: Int, val skipped: Int, val savedBytes: Long) : HomeEvent

    data class Info(val message: String) : HomeEvent
}

data class HomeUiState(
    val queue: List<QueueItem> = emptyList(),
    val defaults: ConversionDefaults = ConversionDefaults(),
    val totalSavedBytes: Long = 0L,
    val convertedCount: Int = 0,
    val isConverting: Boolean = false,
) {
    val convertibleCount: Int
        get() = queue.count {
            it.status == QueueStatus.PENDING ||
                it.status == QueueStatus.PROBING ||
                it.status == QueueStatus.READY
        }

    val hasFinishedItems: Boolean
        get() = queue.any {
            it.status == QueueStatus.DONE ||
                it.status == QueueStatus.FAILED ||
                it.status == QueueStatus.SKIPPED
        }
}

class HomeViewModel(private val app: UltraSlimApp) : ViewModel() {

    private val repo: ConversionRepository = app.conversionRepository
    private val settings = app.settingsRepository
    private val dao = app.database.historyDao()
    private val workManager = WorkManager.getInstance(app)

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Ids of the items enqueued in the most recent conversion run. */
    private var lastRunIds: Set<String> = emptySet()

    /** Set when the user cancels a run, so the summary event reads as a cancellation. */
    private var cancelledRun = false

    private val workRunning: Flow<Boolean> = workManager
        .getWorkInfosForUniqueWorkFlow(ConversionWorker.UNIQUE_WORK_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING } }
        .distinctUntilChanged()

    val uiState: StateFlow<HomeUiState> = combine(
        repo.queue,
        settings.defaults,
        dao.totalSaved(),
        dao.countDone(),
        workRunning,
    ) { queue, defaults, saved, count, running ->
        HomeUiState(
            queue = queue,
            defaults = defaults,
            totalSavedBytes = saved ?: 0L,
            convertedCount = count,
            isConverting = running || queue.any { it.status == QueueStatus.CONVERTING },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    init {
        viewModelScope.launch {
            repo.incoming.collect { uris ->
                if (uris.isNotEmpty()) {
                    repo.consumeIncoming()
                    addItems(uris)
                }
            }
        }
        viewModelScope.launch {
            var wasRunning = false
            workRunning.collect { running ->
                if (wasRunning && !running) emitRunSummary()
                wasRunning = running
            }
        }
    }

    fun addItems(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch { addItemsInternal(uris) }
    }

    /** Adds fresh uris to the queue and probes them. Returns how many were added. */
    private suspend fun addItemsInternal(uris: List<Uri>): Int {
        val existing = repo.queue.value.mapTo(mutableSetOf()) { it.uri }
        val fresh = uris.map { it.toString() }.distinct().filter { it !in existing }
        if (fresh.isEmpty()) return 0

        val items = fresh.map { uriString ->
            val uri = Uri.parse(uriString)
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val (name, size) = resolveMeta(uri)
            QueueItem(
                id = UUID.randomUUID().toString(),
                uri = uriString,
                displayName = name,
                sizeBytes = size,
            )
        }
        repo.addAll(items)
        coroutineScope {
            items.forEach { item -> launch { probeItem(item.id, Uri.parse(item.uri)) } }
        }
        return items.size
    }

    fun cancelConversion() {
        cancelledRun = true
        workManager.cancelUniqueWork(ConversionWorker.UNIQUE_WORK_NAME)
    }

    /**
     * Scans recent MediaStore images (skipping our own Pictures/UltraSlim output),
     * probes each for a gain map, and queues the HDR ones.
     */
    fun scanAlbum() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            try {
                val hdrUris = withContext(Dispatchers.IO) { findHdrImages() }
                val added = if (hdrUris.isEmpty()) 0 else addItemsInternal(hdrUris)
                when {
                    hdrUris.isEmpty() -> _events.emit(HomeEvent.Info("相册中没有找到 HDR 图片"))
                    added == 0 -> _events.emit(HomeEvent.Info("发现的 HDR 图片已在队列中"))
                    else -> _events.emit(HomeEvent.Info("相册中发现 $added 张 HDR 图片，已加入队列"))
                }
            } finally {
                _scanning.value = false
            }
        }
    }

    private suspend fun findHdrImages(): List<Uri> {
        val queued = repo.queue.value.mapTo(mutableSetOf()) { it.uri }
        val candidates = queryRecentImages().filter { it.toString() !in queued }
        if (candidates.isEmpty()) return emptyList()
        val semaphore = Semaphore(SCAN_PARALLELISM)
        return coroutineScope {
            candidates
                .map { uri -> async { if (semaphore.withPermit { probeHasGainMap(uri) }) uri else null } }
                .awaitAll()
                .filterNotNull()
        }
    }

    private fun queryRecentImages(): List<Uri> {
        val uris = mutableListOf<Uri>()
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
            }
        }.toTypedArray()
        runCatching {
            app.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val pathIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    -1
                }
                var scanned = 0
                while (cursor.moveToNext() && scanned < MAX_SCAN_RESULTS) {
                    if (pathIndex >= 0) {
                        val path = cursor.getString(pathIndex)
                        if (path?.contains("UltraSlim", ignoreCase = true) == true) continue
                    }
                    scanned++
                    if (idIndex >= 0) {
                        uris += ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(idIndex),
                        )
                    }
                }
            }
        }
        return uris
    }

    private suspend fun probeHasGainMap(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val probe = runCatching {
            app.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                UltraHdrBridge.nativeProbe(pfd.fd)
            }
        }.getOrNull()
        probe != null && probe.isSuccess && probe.hasGainMap
    }

    /** Applies a named bundle of conversion parameters. */
    fun applyPreset(preset: CompressionPreset) {
        viewModelScope.launch {
            settings.setQuality(preset.quality)
            settings.setGainmapQuality(preset.gainmapQuality)
            settings.setGainmapScale(preset.gainmapScale)
            settings.setResizePercent(preset.resizePercent)
            settings.setSpeedLevel(preset.speedLevel)
        }
    }

    fun removeItem(id: String) = repo.remove(id)

    fun clearFinished() = repo.clearFinished()

    fun retryItem(id: String) {
        val item = repo.find(id) ?: return
        repo.update(id) { it.copy(status = QueueStatus.PENDING, error = null, probe = null) }
        viewModelScope.launch { probeItem(item.id, Uri.parse(item.uri)) }
        startConversion(onlyIds = setOf(id))
    }

    fun retryFailed() {
        val failed = repo.queue.value.filter { it.status == QueueStatus.FAILED }
        if (failed.isEmpty()) return
        failed.forEach { item ->
            repo.update(item.id) { it.copy(status = QueueStatus.PENDING, error = null, probe = null) }
            viewModelScope.launch { probeItem(item.id, Uri.parse(item.uri)) }
        }
        startConversion(onlyIds = failed.mapTo(mutableSetOf()) { it.id })
    }

    fun startConversion(onlyIds: Set<String>? = null) {
        val eligible = repo.queue.value.filter { item ->
            (onlyIds == null || item.id in onlyIds) &&
                (
                    item.status == QueueStatus.PENDING ||
                        item.status == QueueStatus.PROBING ||
                        item.status == QueueStatus.READY
                    )
        }
        if (eligible.isEmpty()) {
            _events.tryEmit(HomeEvent.Info("没有待转换的图片"))
            return
        }
        lastRunIds = eligible.mapTo(mutableSetOf()) { it.id }
        cancelledRun = false
        val request = OneTimeWorkRequestBuilder<ConversionWorker>()
            .setInputData(ConversionWorker.buildInputData(eligible.map { it.uri }, uiState.value.defaults))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(
            ConversionWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    // ---- option setters (persisted) ----

    fun setFormat(format: OutputFormat) =
        viewModelScope.launch { settings.setOutputFormat(format) }

    fun setQuality(quality: Int) = viewModelScope.launch { settings.setQuality(quality) }

    fun setGainmapQuality(quality: Int) = viewModelScope.launch { settings.setGainmapQuality(quality) }

    fun setGainmapScale(scale: Int) = viewModelScope.launch { settings.setGainmapScale(scale) }

    fun setMultiChannel(enabled: Boolean) = viewModelScope.launch { settings.setMultiChannelGainmap(enabled) }

    fun setKeepExif(keep: Boolean) = viewModelScope.launch { settings.setKeepExif(keep) }

    fun setAllowSdr(allow: Boolean) = viewModelScope.launch { settings.setAllowSdr(allow) }

    fun setAutoSave(enabled: Boolean) = viewModelScope.launch { settings.setAutoSave(enabled) }

    fun setSpeedLevel(level: Int) = viewModelScope.launch { settings.setSpeedLevel(level) }

    fun setResizePercent(percent: Int) = viewModelScope.launch { settings.setResizePercent(percent) }

    // ---- internals ----

    private suspend fun probeItem(id: String, uri: Uri) {
        repo.update(id) {
            if (it.status == QueueStatus.PENDING) it.copy(status = QueueStatus.PROBING) else it
        }
        val probe = withContext(Dispatchers.IO) {
            runCatching {
                app.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    UltraHdrBridge.nativeProbe(pfd.fd)
                }
            }.getOrNull()
        }
        // Never clobber a status the worker has already moved on with.
        repo.update(id) {
            if (it.status != QueueStatus.PROBING) {
                it
            } else {
                when {
                    probe == null -> it.copy(status = QueueStatus.FAILED, error = "无法读取此文件")
                    !probe.isSuccess -> it.copy(
                        probe = probe,
                        status = QueueStatus.FAILED,
                        error = probe.error.ifBlank { "无法解析图片" },
                    )

                    else -> it.copy(probe = probe, status = QueueStatus.READY)
                }
            }
        }
    }

    private fun resolveMeta(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = 0L
        runCatching {
            app.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        return (name?.takeIf { it.isNotBlank() } ?: "未命名图片") to size
    }

    private fun emitRunSummary() {
        if (cancelledRun) {
            cancelledRun = false
            lastRunIds = emptySet()
            _events.tryEmit(HomeEvent.Info("已取消转换"))
            return
        }
        val run = repo.queue.value.filter { it.id in lastRunIds }
        lastRunIds = emptySet()
        if (run.isEmpty()) return
        val done = run.count { it.status == QueueStatus.DONE }
        val failed = run.count { it.status == QueueStatus.FAILED }
        val skipped = run.count { it.status == QueueStatus.SKIPPED }
        val saved = run
            .filter { it.status == QueueStatus.DONE }
            .sumOf { ((it.result?.inputBytes ?: 0L) - (it.result?.outputBytes ?: 0L)).coerceAtLeast(0L) }
        _events.tryEmit(HomeEvent.Finished(done = done, failed = failed, skipped = skipped, savedBytes = saved))
    }

    companion object {
        private const val MAX_SCAN_RESULTS = 500
        private const val SCAN_PARALLELISM = 4
    }
}

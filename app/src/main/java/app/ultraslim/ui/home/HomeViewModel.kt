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
import app.ultraslim.convert.InputStore
import app.ultraslim.convert.ConversionRepository
import app.ultraslim.convert.ConversionWorker
import app.ultraslim.convert.QueueItem
import app.ultraslim.convert.QueueStatus
import app.ultraslim.data.ConversionDefaults
import app.ultraslim.data.BatchEntity
import app.ultraslim.data.encode
import app.ultraslim.data.decodeDefaults
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
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
        get() = queue.any { it.status == QueueStatus.DONE }
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
    private var activeBatch: String? = null
    private var scanJob: Job? = null
    private val runDefaults = MutableStateFlow(ConversionDefaults())
    private val _stopping = MutableStateFlow(false)
    val stopping: StateFlow<Boolean> = _stopping.asStateFlow()
    private val _scanProgress = MutableStateFlow("")
    val scanProgress: StateFlow<String> = _scanProgress.asStateFlow()

    /** Shared by album scan and manually added items. */
    private val scannedProbes = java.util.concurrent.ConcurrentHashMap<String, app.ultraslim.bridge.ProbeResult>()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()
    private val _importProgress = MutableStateFlow("")
    val importProgress: StateFlow<String> = _importProgress.asStateFlow()

    private val probePermits = Semaphore(SCAN_PARALLELISM)

    private val workRunning: Flow<Boolean> = workManager
        .getWorkInfosForUniqueWorkFlow(ConversionWorker.UNIQUE_WORK_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED } }
        .distinctUntilChanged()

    val uiState: StateFlow<HomeUiState> = combine(
        repo.queue,
        runDefaults,
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
            repo.awaitReady()
            val latest = app.database.taskDao().latestBatch()
            activeBatch = latest?.id
            runDefaults.value = if (latest != null && repo.queue.value.any { it.batchId == latest.id && it.status !in listOf(QueueStatus.DONE, QueueStatus.FAILED, QueueStatus.SKIPPED) }) decodeDefaults(latest.settings) else settings.defaults.first()
        }
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
        viewModelScope.launch { repo.awaitReady(); addItemsInternal(uris) }
    }

    /** 相册（图集）信息，用于批量导入。 */
    data class AlbumBucket(val id: String, val name: String, val count: Int, val coverUri: String, val latestModified: Long)

    private val _albums = MutableStateFlow<List<AlbumBucket>>(emptyList())
    val albums: StateFlow<List<AlbumBucket>> = _albums.asStateFlow()

    private val _loadingAlbums = MutableStateFlow(false)
    val loadingAlbums: StateFlow<Boolean> = _loadingAlbums.asStateFlow()

    /** 列出设备上的相册（图集），按最新照片时间排序，封面取最新一张。 */
    fun loadAlbums() {
        viewModelScope.launch {
            _loadingAlbums.value = true
            try {
                _albums.value = withContext(Dispatchers.IO) { queryAlbums() }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _events.emit(HomeEvent.Info("无法读取相册：${e.message}"))
            } finally {
                _loadingAlbums.value = false
            }
        }
    }

    private fun queryAlbums(): List<AlbumBucket> {
        data class Bucket(val ids: MutableList<Long>, var latest: Long)
        val buckets = LinkedHashMap<String, Bucket>()
        val names = HashMap<String, String>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        app.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val bucket = cursor.getString(bucketCol) ?: continue
                val date = cursor.getLong(dateCol)
                buckets.getOrPut(bucket) { Bucket(mutableListOf(), Long.MIN_VALUE) }.apply {
                    ids.add(cursor.getLong(idCol))
                    if (date > latest) latest = date
                }
                names.putIfAbsent(bucket, cursor.getString(nameCol) ?: "未命名相册")
            }
        }
        return buckets.map { (id, bucket) ->
            val latestId = bucket.ids.first()  // query is newest-first
            AlbumBucket(
                id = id,
                name = names[id] ?: "未命名相册",
                count = bucket.ids.size,
                coverUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, latestId).toString(),
                latestModified = bucket.latest,
            )
        }.sortedByDescending { it.latestModified }
    }

    /** 把整个相册的照片加入处理列表（自动去重；单次上限 1000 张）。 */
    fun importAlbum(album: AlbumBucket) {
        viewModelScope.launch {
            val uris = withContext(Dispatchers.IO) { queryImages(album.id) }
            if (uris.isEmpty()) { _events.emit(HomeEvent.Info("这个相册没有照片")); return@launch }
            val capped = if (uris.size > 1000) {
                _events.emit(HomeEvent.Info("相册共 ${uris.size} 张，单次最多导入 1000 张"))
                uris.take(1000)
            } else uris
            val added = addItemsInternal(capped)
            _events.emit(HomeEvent.Info(if (added == capped.size) "已导入 $added 张照片" else "已导入 $added 张（${capped.size - added} 张已在列表中）"))
        }
    }

    /** Adds fresh uris to the queue and probes them. Returns how many were added. */
    private suspend fun addItemsInternal(uris: List<Uri>): Int {
        repo.awaitReady()
        val existing = repo.queue.value.mapTo(mutableSetOf()) { it.uri }
        val fresh = uris.map { it.toString() }.distinct().filter { it !in existing }
        if (fresh.isEmpty()) return 0

        val items = mutableListOf<QueueItem>()
        _importing.value = true
        try {
            var index = 0
            for (uriString in fresh) {
                index++
                if (fresh.size > 9) _importProgress.value = "正在导入 ${index}/${fresh.size}…"
                val uri = Uri.parse(uriString)
                try {
                    val (name, size) = withContext(Dispatchers.IO) { resolveMeta(uri) }
                    val id = UUID.randomUUID().toString()
                    val durable = withContext(Dispatchers.IO) { InputStore.persist(app, uri, id) }
                    items += QueueItem(id = id, uri = durable.toString(), displayName = name, sizeBytes = size)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { _events.emit(HomeEvent.Info("导入失败：${e.message}")) }
            }
        } finally {
            _importing.value = false
            _importProgress.value = ""
        }
        repo.addAll(items)
        coroutineScope {
            items.forEach { item -> launch { probeItem(item.id, Uri.parse(item.uri)) } }
        }
        return items.size
    }

    fun cancelConversion() {
        val batch = activeBatch ?: return
        _stopping.value = true
        cancelledRun = true
        viewModelScope.launch { app.database.taskDao().cancel(batch) }
    }

    /**
     * 逐张检测指定范围的增益图，只把 HDR 照片加入队列（带进度、可停止）。
     * 相册批量导入的"仅 HDR"模式和整库扫描共用这条路径。
     */
    fun scanLibraryForHdr() = startHdrScan("整个图库") { queryImages(null) }

    fun scanAlbumForHdr(album: AlbumBucket) = startHdrScan("相册「${album.name}」") { queryImages(album.id) }

    private fun startHdrScan(scopeLabel: String, query: () -> List<Uri>) {
        if (_scanning.value) return
        scanJob = viewModelScope.launch {
            _scanning.value = true
            _scanProgress.value = "正在检查$scopeLabel…"
            try {
                val candidates = withContext(Dispatchers.IO) {
                    val queued = repo.queue.value.mapTo(mutableSetOf()) { it.uri }
                    query().filter { it.toString() !in queued }
                }
                val hdrUris = if (candidates.isEmpty()) emptyList() else filterHdr(candidates)
                val added = if (hdrUris.isEmpty()) 0 else addItemsInternal(hdrUris)
                when {
                    hdrUris.isEmpty() -> _events.emit(HomeEvent.Info("$scopeLabel 中没有找到 HDR 照片"))
                    added == 0 -> _events.emit(HomeEvent.Info("发现的 HDR 图片已在队列中"))
                    else -> _events.emit(HomeEvent.Info("发现 $added 张 HDR 图片，已加入队列"))
                }
            } catch (e: CancellationException) {
                _events.tryEmit(HomeEvent.Info("已停止扫描"))
            } catch (e: Exception) {
                _events.emit(HomeEvent.Info("扫描失败：${e.message}"))
            } finally {
                _scanning.value = false
            }
        }
    }

    private suspend fun filterHdr(candidates: List<Uri>): List<Uri> {
        val found = mutableListOf<Uri>()
        candidates.forEachIndexed { index, uri ->
            coroutineContext.ensureActive()
            if (probeHasGainMap(uri)) found += uri
            _scanProgress.value = "已检查 ${index + 1}/${candidates.size}，找到 ${found.size} 张 HDR"
        }
        return found
    }

    /** 可访问图片，按最新在前；bucketId 非空时只取该相册。跳过我们自己的输出目录，无数量上限。 */
    private fun queryImages(bucketId: String? = null): List<Uri> {
        val uris = mutableListOf<Uri>()
        val selection = buildString {
            append("${MediaStore.Images.Media.MIME_TYPE} IN (?,?,?,?,?)")
            if (bucketId != null) append(" AND ${MediaStore.Images.Media.BUCKET_ID} = ?")
        }
        val args = buildList {
            addAll(arrayOf("image/jpeg", "image/jpg", "image/heic", "image/heif", "image/avif"))
            if (bucketId != null) add(bucketId)
        }
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
            }
        }.toTypedArray()
        app.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args.toTypedArray(),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
            val pathIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                -1
            }
            while (cursor.moveToNext()) {
                if (pathIndex >= 0) {
                    val path = cursor.getString(pathIndex)
                    if (path?.contains("UltraSlim", ignoreCase = true) == true) continue
                }
                if (idIndex >= 0) {
                    uris += ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idIndex),
                    )
                }
            }
        }
        return uris
    }

    private suspend fun probeHasGainMap(uri: Uri): Boolean = probePermits.withPermit {
        withContext(Dispatchers.IO) {
            val probe = runCatching {
                app.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    UltraHdrBridge.nativeProbe(pfd.fd)
                }
            }.getOrNull()
            if (probe != null && probe.isSuccess) scannedProbes[uri.toString()] = probe
            probe != null && probe.isSuccess && probe.hasGainMap
        }
    }

    /** Applies a named bundle of conversion parameters. */
    fun applyPreset(preset: CompressionPreset) {
        if (uiState.value.isConverting) return
        runDefaults.value = runDefaults.value.copy(quality = preset.quality, gainmapQuality = preset.gainmapQuality,
            gainmapScale = preset.gainmapScale, resizePercent = preset.resizePercent, speedLevel = preset.speedLevel)
    }

    fun permissionDenied() { _events.tryEmit(HomeEvent.Info("没有获得相册访问权限，可直接选择图片导入")) }

    fun cancelScan() { scanJob?.cancel() }

    fun removeItem(id: String) {
        val item = repo.find(id) ?: return
        InputStore.deletePersisted(app, Uri.parse(item.uri))
        repo.remove(id)
    }

    fun clearFinished() {
        val doomed = repo.queue.value.filter { it.status == QueueStatus.DONE }
        doomed.forEach { InputStore.deletePersisted(app, Uri.parse(it.uri)) }
        repo.clearFinished()
    }

    fun clearPending() {
        val doomed = repo.queue.value.filter {
            it.status == QueueStatus.PENDING || it.status == QueueStatus.PROBING || it.status == QueueStatus.READY
        }
        doomed.forEach { InputStore.deletePersisted(app, Uri.parse(it.uri)) }
        repo.clearPending()
        viewModelScope.launch { _events.emit(HomeEvent.Info("已移除 ${doomed.size} 张待处理照片")) }
    }

    fun clearAll() {
        val doomed = repo.queue.value.filter { it.status != QueueStatus.CONVERTING }
        doomed.forEach { InputStore.deletePersisted(app, Uri.parse(it.uri)) }
        repo.clearAll()
        viewModelScope.launch { _events.emit(HomeEvent.Info("处理列表已清空（${doomed.size} 张）")) }
    }

    fun retryItem(id: String) {
        if (uiState.value.isConverting) { _events.tryEmit(HomeEvent.Info("请等待当前批次结束后重试")); return }
        val item = repo.find(id) ?: return
        repo.update(id) { it.copy(status = QueueStatus.PENDING, error = null, probe = null) }
        viewModelScope.launch { probeItem(item.id, Uri.parse(item.uri)) }
        startConversion(onlyIds = setOf(id))
    }

    fun retryFailed() {
        if (uiState.value.isConverting) return
        val failed = repo.queue.value.filter { it.status == QueueStatus.FAILED }
        if (failed.isEmpty()) return
        failed.forEach { item ->
            repo.update(item.id) { it.copy(status = QueueStatus.PENDING, error = null, probe = null) }
            viewModelScope.launch { probeItem(item.id, Uri.parse(item.uri)) }
        }
        startConversion(onlyIds = failed.mapTo(mutableSetOf()) { it.id })
    }

    private var starting = false
    fun tryOne() { startConversion(repo.queue.value.firstOrNull { it.status == QueueStatus.READY }?.let { setOf(it.id) } ?: emptySet()) }

    fun startConversion(onlyIds: Set<String>? = null) {
        if (starting || uiState.value.isConverting) return
        starting = true
        viewModelScope.launch {
            try {
                repo.awaitReady()
                val eligible = repo.queue.value.filter { (onlyIds == null || it.id in onlyIds) && it.status in listOf(QueueStatus.PENDING, QueueStatus.PROBING, QueueStatus.READY) }
                if (eligible.isEmpty()) { _events.emit(HomeEvent.Info("没有待转换的图片")); return@launch }
                val batchId = UUID.randomUUID().toString()
                val options = runDefaults.value
                app.database.taskDao().putBatch(BatchEntity(batchId, JSONArray(eligible.map { it.id }).toString(), options.encode()))
                eligible.forEach { q -> repo.update(q.id) { it.copy(batchId = batchId) } }
                repo.flush()
                lastRunIds = eligible.mapTo(mutableSetOf()) { it.id }
                activeBatch = batchId; cancelledRun = false; _stopping.value = false
                val request = OneTimeWorkRequestBuilder<ConversionWorker>()
                    .setInputData(androidx.work.Data.Builder().putString(ConversionWorker.KEY_BATCH, batchId).build())
                    .build()
                workManager.enqueueUniqueWork(ConversionWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
            } catch (e: Exception) { _events.emit(HomeEvent.Info("无法启动转换：${e.message}")) }
            finally { starting = false }
        }
    }

    private fun change(transform: (ConversionDefaults) -> ConversionDefaults) {
        if (!uiState.value.isConverting && !starting) runDefaults.value = transform(runDefaults.value)
    }
    fun setFormat(v: OutputFormat) = change { it.copy(format = v) }
    fun setQuality(v: Int) = change { it.copy(quality = v) }
    fun setGainmapQuality(v: Int) = change { it.copy(gainmapQuality = v) }
    fun setGainmapScale(v: Int) = change { it.copy(gainmapScale = v) }
    fun setMultiChannel(v: Boolean) = change { it.copy(multiChannelGainmap = v) }
    fun setKeepExif(v: Boolean) = change { it.copy(keepExif = v) }
    fun setKeepLocation(v: Boolean) = change { it.copy(keepLocation = v) }
    fun setAllowSdr(v: Boolean) = change { it.copy(allowSdr = v) }
    fun setAutoSave(v: Boolean) = change { it.copy(autoSave = v) }
    fun setOnlySmaller(v: Boolean) = change { it.copy(onlySmaller = v) }
    fun setSpeedLevel(v: Int) = change { it.copy(speedLevel = v) }
    fun setResizePercent(v: Int) = change { it.copy(resizePercent = v) }

    // ---- internals ----

    private suspend fun probeItem(id: String, uri: Uri) = probePermits.withPermit {
        repo.update(id) {
            if (it.status == QueueStatus.PENDING) it.copy(status = QueueStatus.PROBING) else it
        }
        val probe = scannedProbes.remove(uri.toString()) ?: withContext(Dispatchers.IO) {
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
        _stopping.value = false
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
            .sumOf { ((it.result?.inputBytes ?: 0L) - (it.result?.outputBytes ?: 0L)) }
        _events.tryEmit(HomeEvent.Finished(done = done, failed = failed, skipped = skipped, savedBytes = saved))
    }

    companion object {
        private const val SCAN_PARALLELISM = 2
    }
}

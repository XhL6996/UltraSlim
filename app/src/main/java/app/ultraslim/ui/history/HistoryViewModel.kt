package app.ultraslim.ui.history

import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ultraslim.UltraSlimApp
import app.ultraslim.convert.ConversionWorker
import app.ultraslim.convert.MediaStoreTrashHelper
import app.ultraslim.convert.OutputStore
import app.ultraslim.data.HistoryEntity
import app.ultraslim.data.HistoryStats
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HistoryUiState(
    val items: List<HistoryEntity> = emptyList(),
    val totalSavedBytes: Long = 0,
    val convertedCount: Int = 0,
    val busy: Boolean = false,
    val stats: HistoryStats = HistoryStats(),
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val app: UltraSlimApp) : ViewModel() {
    private val dao = app.database.historyDao()
    private val limit = MutableStateFlow(100)
    private val busy = MutableStateFlow(false)
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()
    private val _deleteCandidates = MutableStateFlow<List<HistoryEntity>>(emptyList())
    val deleteCandidates = _deleteCandidates.asStateFlow()

    fun cancelDelete() {
        _deleteCandidates.value = emptyList()
    }

    fun requestDelete(ids: Set<Long>) {
        if (!busy.value) _deleteCandidates.value = uiState.value.items.filter { it.id in ids }
    }

    fun requestDeleteDate(date: java.time.LocalDate, filter: String) {
        if (busy.value) return
        viewModelScope.launch {
            val range = historyDateRange(date)
            _deleteCandidates.value = dao.inDateRange(range.first, range.last + 1)
                .filter { it.matchesHistoryFilter(filter) }
        }
    }

    fun deleteConfirmed() {
        if (busy.value) return
        val records = _deleteCandidates.value
        _deleteCandidates.value = emptyList()
        busy.value = true
        viewModelScope.launch {
            try {
                val active = androidx.work.WorkManager.getInstance(app)
                    .getWorkInfosForUniqueWorkFlow(ConversionWorker.UNIQUE_WORK_NAME)
                    .first().any { !it.state.isFinished }
                if (active) {
                    _events.emit("请等待当前转换结束后删除记录")
                    return@launch
                }
                records.forEach { OutputStore.delete(app, it.id) }
                _events.emit("已删除 ${records.size} 条记录，已保存到相册的图片不受影响")
            } finally {
                busy.value = false
            }
        }
    }

    val uiState: StateFlow<HistoryUiState> = combine(
        limit.flatMapLatest { dao.flowPage(it) },
        dao.flowStats(),
        busy,
    ) { items, stats, working ->
        HistoryUiState(
            items = items,
            totalSavedBytes = stats.totalSavedBytes,
            convertedCount = stats.countDone,
            busy = working,
            stats = stats,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryUiState())

    fun loadMore() {
        limit.value += 100
    }

    fun saveSelected(ids: Set<Long>) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            var done = 0
            var failed = 0
            try {
                uiState.value.items.filter { it.id in ids && it.saveState != "SAVED" }.forEach {
                    try {
                        if (OutputStore.save(app, it).saveState == "SAVED") done++ else failed++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                    }
                }
                _events.emit("已保存 $done 张，失败 $failed 张；失败成品仍在应用内")
            } finally {
                busy.value = false
            }
        }
    }

    /** 原图 → MediaStore URI 的解析缓存（含逐条查询）；Default 线程解析，选择时只查表。 */
    private val trashableCache = MutableStateFlow<Map<Long, Uri>>(emptyMap())
    val trashableOriginals: StateFlow<Map<Long, Uri>> = trashableCache.asStateFlow()

    /** 补齐缺缓存条目的解析；页面加载或筛选变化时调用，重复调用只解析增量。 */
    fun ensureTrashableResolved(items: List<HistoryEntity>) {
        val missing = items.filter { it.id !in trashableCache.value }.map { it.id to it.inputUri }
        if (missing.isEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val resolved = missing.mapNotNull { (id, uri) ->
                MediaStoreTrashHelper.resolveToMediaStoreImageUri(app, Uri.parse(uri))?.let { id to it }
            }
            trashableCache.update { it + resolved }
        }
    }

    private fun trashableOriginalUris(ids: Set<Long>): List<Uri> =
        ids.mapNotNull { trashableCache.value[it] }

    fun requestTrashSelected(ids: Set<Long>): IntentSender? {
        val uris = trashableOriginalUris(ids)
        if (uris.isEmpty()) {
            _events.tryEmit("所选照片的原图不在系统相册管理范围内")
            return null
        }
        val sender = MediaStoreTrashHelper.createTrashIntentSender(app, uris)
        if (sender == null) {
            _events.tryEmit("系统不支持或无法获取回收站权限")
        }
        return sender
    }

    fun onSelectedOriginalsTrashed(count: Int) {
        _events.tryEmit("已将 $count 张原图移至系统相册回收站（30天内可恢复）")
    }

    fun clearAll() {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val active = androidx.work.WorkManager.getInstance(app)
                    .getWorkInfosForUniqueWorkFlow(ConversionWorker.UNIQUE_WORK_NAME)
                    .first().any { !it.state.isFinished }
                if (active) {
                    _events.emit("请等待当前转换结束后清空历史")
                    return@launch
                }
                val records = dao.all()
                records.forEach { entity ->
                    OutputStore.delete(app, entity.id)
                }
                app.conversionRepository.clearFinished()
            } finally {
                busy.value = false
            }
        }
    }
}

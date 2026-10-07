package app.ultraslim.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ultraslim.UltraSlimApp
import app.ultraslim.data.HistoryEntity
import app.ultraslim.convert.ConversionWorker
import app.ultraslim.convert.OutputStore
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HistoryUiState(val items: List<HistoryEntity> = emptyList(), val totalSavedBytes: Long = 0,
    val convertedCount: Int = 0, val busy: Boolean = false)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val app: UltraSlimApp) : ViewModel() {
    private val dao = app.database.historyDao()
    private val limit = MutableStateFlow(100)
    private val busy = MutableStateFlow(false)
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()
    private val _deleteCandidates = MutableStateFlow<List<HistoryEntity>>(emptyList())
    val deleteCandidates = _deleteCandidates.asStateFlow()
    fun cancelDelete() { _deleteCandidates.value = emptyList() }
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
                val active = androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ConversionWorker.UNIQUE_WORK_NAME).first().any { !it.state.isFinished }
                if (active) { _events.emit("请等待当前转换结束后删除记录"); return@launch }
                records.forEach { OutputStore.delete(app, it.id) }
                _events.emit("已删除 ${records.size} 条记录，已保存到相册的图片不受影响")
            } finally { busy.value = false }
        }
    }
    val uiState: StateFlow<HistoryUiState> = combine(limit.flatMapLatest { dao.flowPage(it) }, dao.totalSaved(), dao.countDone(), busy) { items, saved, count, working ->
        HistoryUiState(items, saved ?: 0, count, working)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryUiState())
    fun loadMore() { limit.value += 100 }
    fun saveSelected(ids: Set<Long>) {
        if(busy.value) return
        busy.value = true
        viewModelScope.launch {
            var done = 0; var failed = 0
            try {
                uiState.value.items.filter { it.id in ids && it.saveState != "SAVED" }.forEach {
                    try { if(OutputStore.save(app,it).saveState == "SAVED") done++ else failed++ }
                    catch(e: kotlinx.coroutines.CancellationException) { throw e }
                    catch(e: Exception) { failed++ }
                }
                _events.emit("已保存 $done 张，失败 $failed 张；失败成品仍在应用内")
            } finally { busy.value = false }
        }
    }
    fun clearAll() {
        if(busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val active = androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ConversionWorker.UNIQUE_WORK_NAME).first().any { !it.state.isFinished }
                if (active) { _events.emit("请等待当前转换结束后清空历史"); return@launch }
                val records = dao.all()
                records.forEach { entity ->
                    OutputStore.delete(app, entity.id)
                }
                app.conversionRepository.clearFinished()
            } finally { busy.value = false }
        }
    }
}

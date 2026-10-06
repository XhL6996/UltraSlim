package app.ultraslim.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ultraslim.UltraSlimApp
import app.ultraslim.data.HistoryEntity
import app.ultraslim.convert.ConversionWorker
import app.ultraslim.convert.OutputStore
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

 data class HistoryUiState(val items: List<HistoryEntity> = emptyList(), val totalSavedBytes: Long = 0,
    val convertedCount: Int = 0, val busy: Boolean = false)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val app: UltraSlimApp) : ViewModel() {
    private val dao = app.database.historyDao()
    private val limit = MutableStateFlow(100)
    private val busy = MutableStateFlow(false)
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()
    val uiState: StateFlow<HistoryUiState> = combine(limit.flatMapLatest { dao.flowPage(it) }, dao.totalSaved(), dao.countDone(), busy) { items, saved, count, working ->
        HistoryUiState(items, saved ?: 0, count, working)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryUiState())
    fun loadMore() { limit.value += 100 }
    fun saveSelected(ids: Set<Long>) {
        if(busy.value) return
        viewModelScope.launch {
            busy.value = true
            var done = 0; var failed = 0
            try {
                uiState.value.items.filter { it.id in ids }.forEach {
                    try { if(OutputStore.save(app,it).saveState == "SAVED") done++ else failed++ } catch(e: Exception) { failed++ }
                }
                _events.emit("已保存 $done 张，失败 $failed 张；失败成品仍在应用内")
            } finally { busy.value = false }
        }
    }
    fun clearAll() {
        if(busy.value) return
        viewModelScope.launch {
            busy.value = true
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

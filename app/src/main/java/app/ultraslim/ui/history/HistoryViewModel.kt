package app.ultraslim.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ultraslim.UltraSlimApp
import app.ultraslim.data.HistoryEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryUiState(
    val items: List<HistoryEntity> = emptyList(),
    val totalSavedBytes: Long = 0L,
    val convertedCount: Int = 0,
)

class HistoryViewModel(app: UltraSlimApp) : ViewModel() {

    private val dao = app.database.historyDao()

    val uiState: StateFlow<HistoryUiState> = combine(
        dao.flowAll(),
        dao.totalSaved(),
        dao.countDone(),
    ) { items, saved, count ->
        HistoryUiState(
            items = items,
            totalSavedBytes = saved ?: 0L,
            convertedCount = count,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryUiState())

    fun clearAll() {
        viewModelScope.launch { dao.deleteAll() }
    }
}

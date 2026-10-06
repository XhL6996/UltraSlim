package app.ultraslim.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ultraslim.UltraSlimApp
import app.ultraslim.convert.MediaStoreSaver
import app.ultraslim.data.HistoryEntity
import app.ultraslim.model.OutputFormat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface DetailEvent {
    data class Message(val text: String) : DetailEvent

    data object Deleted : DetailEvent
}

data class DetailUiState(
    val entity: HistoryEntity? = null,
    val saving: Boolean = false,
)

class DetailViewModel(private val app: UltraSlimApp, private val historyId: Long) : ViewModel() {

    private val dao = app.database.historyDao()

    private val saving = MutableStateFlow(false)

    val uiState: StateFlow<DetailUiState> = combine(
        dao.flowById(historyId),
        saving,
    ) { entity, isSaving ->
        DetailUiState(entity = entity, saving = isSaving)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetailUiState())

    private val _events = MutableSharedFlow<DetailEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<DetailEvent> = _events.asSharedFlow()

    /** Copies a cache-file output into the album and updates the record. */
    fun saveToAlbum() {
        val entity = uiState.value.entity ?: return
        if (entity.outputUri.startsWith("content://") || saving.value) return
        viewModelScope.launch {
            saving.value = true
            val file = File(entity.outputUri)
            val saved = withContext(Dispatchers.IO) {
                MediaStoreSaver.save(app, file, entity.displayName, OutputFormat.parse(entity.format))
            }
            saving.value = false
            if (saved != null) {
                dao.update(entity.copy(outputUri = saved.toString()))
                _events.emit(DetailEvent.Message("已保存到相册/UltraSlim"))
            } else {
                _events.emit(DetailEvent.Message("保存失败，无法写入相册"))
            }
        }
    }

    fun deleteRecord() {
        viewModelScope.launch {
            val entity = uiState.value.entity
            dao.deleteById(historyId)
            if (entity != null && !entity.outputUri.startsWith("content://")) {
                withContext(Dispatchers.IO) { runCatching { File(entity.outputUri).delete() } }
            }
            _events.emit(DetailEvent.Deleted)
        }
    }
}

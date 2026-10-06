package app.ultraslim.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ultraslim.convert.InputStore
import app.ultraslim.convert.OutputStore
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
    val loading: Boolean = true,
)

class DetailViewModel(private val app: UltraSlimApp, private val historyId: Long) : ViewModel() {

    private val dao = app.database.historyDao()

    private val saving = MutableStateFlow(false)

    val uiState: StateFlow<DetailUiState> = combine(
        dao.flowById(historyId),
        saving,
    ) { entity, isSaving ->
        DetailUiState(entity = entity, saving = isSaving, loading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetailUiState())

    private val _events = MutableSharedFlow<DetailEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<DetailEvent> = _events.asSharedFlow()

    /** Copies a cache-file output into the album and updates the record. */
    fun saveToAlbum() {
        val entity = uiState.value.entity ?: return
        if (entity.outputUri.startsWith("content://") || saving.value) return
        viewModelScope.launch {
            saving.value = true
            try {
                val saved = OutputStore.save(app, entity)
                _events.emit(DetailEvent.Message(if(saved.saveState == "SAVED") "已保存到相册/UltraSlim" else "保存失败，成品仍保留；可重试或导出文件"))
            } catch(e: Exception) { _events.emit(DetailEvent.Message("保存失败：${e.message}")) }
            finally { saving.value = false }
        }
    }

    fun exportTo(uri: android.net.Uri) {
        val entity = uiState.value.entity ?: return
        viewModelScope.launch {
            saving.value = true
            try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(OutputStore.uri(app, entity.outputUri))!!.use { input ->
                        app.contentResolver.openOutputStream(uri)!!.use { output -> input.copyTo(output) }
                    }
                }
                _events.emit(DetailEvent.Message("文件已导出"))
            } catch(e: Exception) { _events.emit(DetailEvent.Message("导出失败：${e.message}")) }
            finally { saving.value = false }
        }
    }

    fun relinkSource(uri: android.net.Uri) {
        val row = uiState.value.entity ?: return
        if(saving.value) return
        viewModelScope.launch {
            saving.value = true
            try {
                val durable = withContext(Dispatchers.IO) { InputStore.persist(app, uri, java.util.UUID.randomUUID().toString()) }
                dao.update(row.copy(inputUri = durable.toString()))
                _events.emit(DetailEvent.Message("原图已重新关联"))
            } catch(e: Exception) { _events.emit(DetailEvent.Message("无法关联原图：${e.message}")) }
            finally { saving.value = false }
        }
    }

    fun deleteRecord() {
        if (saving.value) return
        viewModelScope.launch {
            OutputStore.delete(app, historyId)
            _events.emit(DetailEvent.Deleted)
        }
    }
}

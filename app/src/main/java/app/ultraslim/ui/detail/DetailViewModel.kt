package app.ultraslim.ui.detail

import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ultraslim.UltraSlimApp
import app.ultraslim.convert.InputStore
import app.ultraslim.convert.MediaStoreTrashHelper
import app.ultraslim.convert.OutputStore
import app.ultraslim.data.HistoryEntity
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
    val originalTrashed: Boolean = false,
    val canTrashOriginal: Boolean = false,
)

class DetailViewModel(private val app: UltraSlimApp, private val historyId: Long) : ViewModel() {

    private val dao = app.database.historyDao()

    private val saving = MutableStateFlow(false)
    private val originalTrashed = MutableStateFlow(false)
    private val canTrash = MutableStateFlow(false)

    val uiState: StateFlow<DetailUiState> = combine(
        dao.flowById(historyId),
        saving,
        originalTrashed,
        canTrash,
    ) { entity, isSaving, isTrashed, canTrashVal ->
        DetailUiState(
            entity = entity,
            saving = isSaving,
            loading = false,
            originalTrashed = isTrashed,
            canTrashOriginal = canTrashVal && !isTrashed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetailUiState())

    init {
        viewModelScope.launch {
            dao.flowById(historyId).collect { entity ->
                if (entity != null) {
                    val uri = Uri.parse(entity.inputUri)
                    withContext(Dispatchers.IO) {
                        val trashed = MediaStoreTrashHelper.isOriginalTrashed(app, uri)
                        val accessible = MediaStoreTrashHelper.isOriginalAccessible(app, uri)
                        val resolvable = MediaStoreTrashHelper.resolveToMediaStoreImageUri(app, uri) != null
                        originalTrashed.value = trashed
                        canTrash.value = MediaStoreTrashHelper.isTrashSupported && resolvable && accessible && !trashed
                    }
                }
            }
        }
    }

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
                _events.emit(DetailEvent.Message(if (saved.saveState == "SAVED") "已保存到相册/UltraSlim" else "保存失败，成品仍保留；可重试或导出文件"))
            } catch (e: Exception) {
                _events.emit(DetailEvent.Message("保存失败：${e.message}"))
            } finally {
                saving.value = false
            }
        }
    }

    fun exportTo(uri: Uri) {
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
            } catch (e: Exception) {
                _events.emit(DetailEvent.Message("导出失败：${e.message}"))
            } finally {
                saving.value = false
            }
        }
    }

    fun relinkSource(uri: Uri) {
        val row = uiState.value.entity ?: return
        if (saving.value) return
        viewModelScope.launch {
            saving.value = true
            try {
                val durable = withContext(Dispatchers.IO) { InputStore.persist(app, uri, java.util.UUID.randomUUID().toString()) }
                dao.update(row.copy(inputUri = durable.toString()))
                val trashed = MediaStoreTrashHelper.isOriginalTrashed(app, durable)
                val accessible = MediaStoreTrashHelper.isOriginalAccessible(app, durable)
                val resolvable = MediaStoreTrashHelper.resolveToMediaStoreImageUri(app, durable) != null
                originalTrashed.value = trashed
                canTrash.value = MediaStoreTrashHelper.isTrashSupported && resolvable && accessible && !trashed
                _events.emit(DetailEvent.Message("原图已重新关联"))
            } catch (e: Exception) {
                _events.emit(DetailEvent.Message("无法关联原图：${e.message}"))
            } finally {
                saving.value = false
            }
        }
    }

    fun requestTrashOriginal(): IntentSender? {
        val entity = uiState.value.entity ?: return null
        val uri = Uri.parse(entity.inputUri)
        val sender = MediaStoreTrashHelper.createTrashIntentSender(app, listOf(uri))
        if (sender == null) {
            _events.tryEmit(DetailEvent.Message("原图不在系统相册管理范围内，无法移入回收站"))
        }
        return sender
    }

    fun onOriginalTrashed() {
        originalTrashed.value = true
        canTrash.value = false
        _events.tryEmit(DetailEvent.Message("原图已移入系统相册回收站（30天内可恢复）"))
    }

    fun deleteRecord() {
        if (saving.value) return
        viewModelScope.launch {
            OutputStore.delete(app, historyId)
            _events.emit(DetailEvent.Deleted)
        }
    }
}

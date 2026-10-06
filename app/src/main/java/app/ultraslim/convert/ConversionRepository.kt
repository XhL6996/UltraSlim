package app.ultraslim.convert

import android.net.Uri
import app.ultraslim.bridge.ProbeResult
import app.ultraslim.bridge.TranscodeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class QueueStatus { PENDING, PROBING, READY, CONVERTING, DONE, FAILED, SKIPPED }

data class QueueItem(
    val id: String,
    /** Source content uri, as string. */
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val probe: ProbeResult? = null,
    val status: QueueStatus = QueueStatus.PENDING,
    /** Overall queue progress (0–100) while this item is being converted. */
    val progress: Int = 0,
    val result: TranscodeResult? = null,
    /** MediaStore content uri, or cache file path when auto-save is off. */
    val outputUri: String? = null,
    val error: String? = null,
    /** Room history id, set once the conversion succeeded. */
    val historyId: Long? = null,
)

/**
 * Same-process, in-memory conversion queue shared between the UI and
 * [ConversionWorker]. Also carries URIs arriving via share intents.
 */
class ConversionRepository {

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()

    private val _incoming = MutableStateFlow<List<Uri>>(emptyList())
    val incoming: StateFlow<List<Uri>> = _incoming.asStateFlow()

    fun submitIncoming(uris: List<Uri>) {
        if (uris.isNotEmpty()) _incoming.update { it + uris }
    }

    fun consumeIncoming() {
        _incoming.value = emptyList()
    }

    fun addAll(items: List<QueueItem>) {
        _queue.update { it + items }
    }

    fun remove(id: String) {
        _queue.update { list -> list.filterNot { it.id == id } }
    }

    fun update(id: String, transform: (QueueItem) -> QueueItem) {
        _queue.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    fun updateByUri(uri: String, transform: (QueueItem) -> QueueItem) {
        _queue.update { list -> list.map { if (it.uri == uri) transform(it) else it } }
    }

    fun find(id: String): QueueItem? = _queue.value.firstOrNull { it.id == id }

    fun findByUri(uri: String): QueueItem? = _queue.value.firstOrNull { it.uri == uri }

    /** Removes items in a terminal state (done / failed / skipped). */
    fun clearFinished() {
        _queue.update { list ->
            list.filterNot {
                it.status == QueueStatus.DONE ||
                    it.status == QueueStatus.FAILED ||
                    it.status == QueueStatus.SKIPPED
            }
        }
    }
}

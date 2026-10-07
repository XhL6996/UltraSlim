package app.ultraslim.convert

import android.net.Uri
import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.PrimaryKey
import app.ultraslim.data.TaskDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import app.ultraslim.bridge.ProbeResult
import app.ultraslim.bridge.TranscodeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class QueueStatus { PENDING, PROBING, READY, CONVERTING, DONE, FAILED, SKIPPED }

@Entity(tableName = "tasks")
data class QueueItem(
    @PrimaryKey val id: String,
    /** Source content uri, as string. */
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    @Embedded(prefix = "probe_") val probe: ProbeResult? = null,
    val status: QueueStatus = QueueStatus.PENDING,
    /** Overall queue progress (0–100) while this item is being converted. */
    val progress: Int = 0,
    @Embedded(prefix = "result_") val result: TranscodeResult? = null,
    /** MediaStore content uri, or durable private file path when auto-save is off. */
    val outputUri: String? = null,
    val error: String? = null,
    /** Room history id, set once the conversion succeeded. */
    val historyId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val batchId: String = "",
)

/**
 * Durable conversion queue shared between the UI and
 * [ConversionWorker]. Also carries URIs arriving via share intents.
 */
class ConversionRepository(private val dao: TaskDao, scope: CoroutineScope) {
    private val ready = CompletableDeferred<Unit>()
    private val writes = Channel<Pair<List<QueueItem>, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
    suspend fun awaitReady() = ready.await()
    suspend fun flush() {
        ready.await()
        val ack = CompletableDeferred<Unit>()
        synchronized(this) { writes.trySend(_queue.value to ack).getOrThrow() }
        ack.await()
    }
    private fun persist() { writes.trySend(_queue.value to CompletableDeferred()) }


    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()

    private val _incoming = MutableStateFlow<List<Uri>>(emptyList())
    val incoming: StateFlow<List<Uri>> = _incoming.asStateFlow()

    init {
        scope.launch {
            try {
                _queue.value = dao.all().map { if (it.status == QueueStatus.PROBING || it.status == QueueStatus.CONVERTING) it.copy(status = QueueStatus.READY) else it }
                ready.complete(Unit)
                for ((items, ack) in writes) {
                    try { dao.snapshot(items); ack.complete(Unit) } catch (e: Exception) { ack.completeExceptionally(e) }
                }
            } catch (e: Exception) { ready.completeExceptionally(e) }
        }
    }

    fun submitIncoming(uris: List<Uri>) {
        if (uris.isNotEmpty()) _incoming.update { it + uris }
    }

    fun consumeIncoming() {
        _incoming.value = emptyList()
    }

    @Synchronized fun addAll(items: List<QueueItem>) {
        _queue.update { existing -> existing + items.filter { item -> existing.none { it.uri == item.uri } } }; persist()
    }

    @Synchronized fun remove(id: String) {
        _queue.update { list -> list.filterNot { it.id == id && it.status != QueueStatus.CONVERTING } }; persist()
    }

    @Synchronized fun removeIds(ids: Collection<String>) {
        val set = ids.toSet()
        _queue.update { list -> list.filterNot { it.id in set && it.status != QueueStatus.CONVERTING } }
        persist()
    }

    @Synchronized fun update(id: String, transform: (QueueItem) -> QueueItem) {
        _queue.update { list -> list.map { if (it.id == id) transform(it) else it } }; persist()
    }

    @Synchronized fun updateByUri(uri: String, transform: (QueueItem) -> QueueItem) {
        _queue.update { list -> list.map { if (it.uri == uri) transform(it) else it } }; persist()
    }

    @Synchronized fun claim(id: String, batchId: String): QueueItem? {
        val item = _queue.value.firstOrNull { it.id == id && it.status !in listOf(QueueStatus.DONE, QueueStatus.SKIPPED, QueueStatus.CONVERTING) } ?: return null
        val claimed = item.copy(status = QueueStatus.CONVERTING, error = null, batchId = batchId)
        _queue.value = _queue.value.map { if(it.id == id) claimed else it }; persist()
        return claimed
    }

    fun find(id: String): QueueItem? = _queue.value.firstOrNull { it.id == id }

    fun findByUri(uri: String): QueueItem? = _queue.value.firstOrNull { it.uri == uri }

    /** Failed and skipped items remain available for inspection and retry. */
    @Synchronized fun clearFinished() {
        _queue.update { list ->
            list.filterNot {
                it.status == QueueStatus.DONE
            }
        }
        persist()
    }

    /** 待处理项（未在转换中的）：PENDING / PROBING / READY。 */
    @Synchronized fun clearPending() {
        _queue.update { list ->
            list.filterNot { it.status == QueueStatus.PENDING || it.status == QueueStatus.PROBING || it.status == QueueStatus.READY }
        }
        persist()
    }

    /** 全部清除；正在转换的条目等它落地后再清。 */
    @Synchronized fun clearAll() {
        _queue.update { list ->
            list.filterNot { it.status != QueueStatus.CONVERTING }
        }
        persist()
    }
}

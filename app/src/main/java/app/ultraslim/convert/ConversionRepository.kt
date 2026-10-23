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
    /** MediaStore/SAF content URI, or a durable private file path before publication. */
    val outputUri: String? = null,
    val error: String? = null,
    /** Room history id, set once the conversion succeeded. */
    val historyId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val batchId: String = "",
)

/**
 * Durable conversion queue shared between the UI and
 * [ConversionService]. Also carries URIs arriving via share intents.
 */
class ConversionRepository(private val dao: TaskDao, scope: CoroutineScope) {
    private val ready = CompletableDeferred<Unit>()
    // One wakeup and one current snapshot, regardless of batch size. flush() is a revision barrier.
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private var revision = 0L
    private var persistedRevision = 0L
    private val barriers = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
    suspend fun awaitReady() = ready.await()
    suspend fun flush() {
        ready.await()
        val ack = CompletableDeferred<Unit>()
        synchronized(this) {
            if (persistedRevision >= revision) ack.complete(Unit)
            else {
                barriers += revision to ack
                writes.trySend(Unit).getOrThrow()
            }
        }
        ack.await()
    }
    private fun persist() { revision++; writes.trySend(Unit).getOrThrow() }

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()

    private val _incoming = MutableStateFlow<List<Uri>>(emptyList())
    val incoming: StateFlow<List<Uri>> = _incoming.asStateFlow()

    init {
        scope.launch {
            try {
                var persisted = dao.all().associateBy { it.id }
                synchronized(this@ConversionRepository) {
                    _queue.value = persisted.values.map {
                        when (it.status) {
                            QueueStatus.CONVERTING -> it.copy(status = QueueStatus.READY, error = "上次转换被中断，请继续；已完成图片保留")
                            QueueStatus.PROBING -> it.copy(status = QueueStatus.READY)
                            else -> it
                        }
                    }
                    if (_queue.value.any { persisted[it.id] != it }) persist()
                }
                ready.complete(Unit)
                for (ignored in writes) {
                    val snapshot = synchronized(this@ConversionRepository) {
                        if (persistedRevision >= revision) null else _queue.value to revision
                    } ?: continue // A flush wakeup may already be covered by the previous write.
                    val (items, target) = snapshot
                    val next = items.associateBy { it.id }
                    val changed = items.filter { persisted[it.id] != it }
                    val removed = persisted.keys.filter { it !in next }
                    var failure: Exception? = null
                    try {
                        // Changes can cancel each other before this snapshot is drained.
                        if (changed.isNotEmpty() || removed.isNotEmpty()) dao.applyChanges(changed, removed)
                        persisted = next
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        failure = e
                    }
                    synchronized(this@ConversionRepository) {
                        if (failure == null) persistedRevision = target
                        val completed = barriers.filter { it.first <= target }
                        barriers.removeAll(completed.toSet())
                        completed.forEach { (_, ack) ->
                            if (failure == null) ack.complete(Unit) else ack.completeExceptionally(failure)
                        }
                    }
                }
            } catch (e: Exception) {
                ready.completeExceptionally(e)
                synchronized(this@ConversionRepository) {
                    writes.close(e)
                    barriers.forEach { it.second.completeExceptionally(e) }
                    barriers.clear()
                }
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    fun submitIncoming(uris: List<Uri>) {
        if (uris.isNotEmpty()) _incoming.update { it + uris }
    }

    fun consumeIncoming() {
        _incoming.value = emptyList()
    }

    /** Called with the repository monitor held; unchanged snapshots need no write or emission. */
    private fun replaceQueue(items: List<QueueItem>) {
        if (items == _queue.value) return
        _queue.value = items
        persist()
    }

    @Synchronized fun addAll(items: List<QueueItem>) {
        if (items.isEmpty()) return
        val existing = _queue.value
        val uris = existing.mapTo(HashSet()) { it.uri }
        val added = items.filter { uris.add(it.uri) }
        if (added.isNotEmpty()) replaceQueue(existing + added)
    }

    data class RemovedItem(val item: QueueItem, val index: Int)

    @Synchronized fun remove(id: String): RemovedItem? {
        val list = _queue.value
        val index = list.indexOfFirst { it.id == id && it.status != QueueStatus.CONVERTING }
        if (index < 0) return null
        val removed = RemovedItem(list[index], index)
        _queue.value = list.filterNot { it.id == id }
        persist()
        return removed
    }

    @Synchronized fun restore(removed: RemovedItem): QueueItem? {
        if (_queue.value.any { it.id == removed.item.id || it.uri == removed.item.uri }) return null
        val item = removed.item.let {
            if (it.status == QueueStatus.PROBING) it.copy(status = QueueStatus.PENDING) else it
        }
        _queue.value = _queue.value.toMutableList().apply { add(removed.index.coerceIn(0, size), item) }
        persist()
        return item
    }

    @Synchronized fun removeIds(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        replaceQueue(_queue.value.filterNot { it.id in set && it.status != QueueStatus.CONVERTING })
    }

    @Synchronized fun update(id: String, transform: (QueueItem) -> QueueItem) {
        replaceQueue(_queue.value.map { if (it.id == id) transform(it) else it })
    }

    /** 批量 update：一次 map + 一次 persist，避免逐条调用造成 O(n²) 持久化写入放大。 */
    @Synchronized fun updateAll(ids: Collection<String>, transform: (QueueItem) -> QueueItem) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        replaceQueue(_queue.value.map { if (it.id in set) transform(it) else it })
    }

    /** Claim a bounded import batch in one snapshot; removed/started items need no probe. */
    @Synchronized internal fun beginProbing(ids: Collection<String>): List<QueueItem> {
        if (ids.isEmpty()) return emptyList()
        val selected = ids.toSet()
        val probing = mutableListOf<QueueItem>()
        replaceQueue(_queue.value.map { item ->
            if (item.id in selected && item.status == QueueStatus.PENDING) {
                item.copy(status = QueueStatus.PROBING).also { probing += it }
            } else item
        })
        return probing
    }

    /** Publish individual results together without overwriting a worker claim or removed row. */
    @Synchronized internal fun finishProbing(results: Map<String, ProbeResult?>) {
        if (results.isEmpty()) return
        replaceQueue(_queue.value.map { item ->
            if (item.status != QueueStatus.PROBING || !results.containsKey(item.id)) item
            else when (val probe = results[item.id]) {
                null -> item.copy(status = QueueStatus.FAILED, error = "无法读取此文件")
                else -> if (probe.isSuccess) item.copy(probe = probe, status = QueueStatus.READY)
                else item.copy(probe = probe, status = QueueStatus.FAILED,
                    error = probe.error.ifBlank { "无法解析图片" })
            }
        })
    }

    @Synchronized fun updateByUri(uri: String, transform: (QueueItem) -> QueueItem) {
        replaceQueue(_queue.value.map { if (it.uri == uri) transform(it) else it })
    }

    @Synchronized fun claim(id: String, batchId: String): QueueItem? {
        val item = _queue.value.firstOrNull { it.id == id && it.status !in listOf(QueueStatus.DONE, QueueStatus.SKIPPED, QueueStatus.CONVERTING) } ?: return null
        val claimed = item.copy(status = QueueStatus.CONVERTING, error = null, batchId = batchId)
        _queue.value = _queue.value.map { if(it.id == id) claimed else it }; persist()
        return claimed
    }

    fun find(id: String): QueueItem? = _queue.value.firstOrNull { it.id == id }

    /** Failed and skipped items remain available for inspection and retry. */
    @Synchronized fun clearFinished() {
        replaceQueue(_queue.value.filterNot { it.status == QueueStatus.DONE })
    }

    /** 待处理项（未在转换中的）：PENDING / PROBING / READY。 */
    @Synchronized fun clearPending() {
        replaceQueue(_queue.value.filterNot {
            it.status == QueueStatus.PENDING || it.status == QueueStatus.PROBING || it.status == QueueStatus.READY
        })
    }

    /** 全部清除；正在转换的条目等它落地后再清。 */
    @Synchronized fun clearAll() {
        replaceQueue(_queue.value.filter { it.status == QueueStatus.CONVERTING })
    }
}

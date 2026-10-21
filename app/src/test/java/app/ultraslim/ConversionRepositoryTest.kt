package app.ultraslim

import app.ultraslim.convert.ConversionRepository
import app.ultraslim.convert.QueueItem
import app.ultraslim.convert.QueueStatus
import app.ultraslim.data.BatchEntity
import app.ultraslim.data.TaskDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class ConversionRepositoryTest {
    @Test(timeout = 10_000)
    fun importsDeduplicateExistingAndIncomingUrisWithoutReordering() = runBlocking {
        val fixture = Fixture(listOf(item("existing")))
        try {
            val incoming = (0 until 2000).flatMap { index ->
                listOf(item("$index"), item("duplicate-$index").copy(uri = "content://$index"))
            }
            fixture.repo.addAll(listOf(item("duplicate-existing").copy(uri = "content://existing")) + incoming)
            val flush = async(start = CoroutineStart.UNDISPATCHED) { fixture.repo.flush() }
            fixture.dispatcher.drain()
            flush.await()
            assertEquals(listOf("existing") + (0 until 2000).map { "$it" }, fixture.repo.queue.value.map { it.id })
            assertEquals(fixture.repo.queue.value, fixture.dao.rows)
            assertEquals(1, fixture.dao.writes.size)
            assertEquals(2000, fixture.dao.writes.single().first.size)
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun noOpMutationsDoNotWakeWriterOrReplaceSnapshot() = runBlocking {
        val fixture = Fixture(listOf(item("failed").copy(status = QueueStatus.FAILED)))
        try {
            val repo = fixture.repo
            val original = repo.queue.value
            repo.addAll(emptyList())
            repo.addAll(listOf(item("duplicate").copy(uri = "content://failed")))
            repo.update("missing") { error("Missing item must not be transformed") }
            repo.update("failed") { it.copy() }
            repo.updateByUri("content://missing") { error("Missing URI must not be transformed") }
            repo.updateByUri("content://failed") { it.copy() }
            repo.updateAll(emptyList()) { error("Empty selection must not be transformed") }
            repo.updateAll(listOf("missing", "failed", "failed")) { it.copy() }
            repo.removeIds(emptyList())
            repo.removeIds(listOf("missing"))
            assertNull(repo.remove("missing"))
            repo.clearFinished()
            repo.clearPending()
            repo.flush()
            assertSame(original, repo.queue.value)
            assertEquals(0, fixture.dispatcher.pending)
            assertTrue(fixture.dao.writes.isEmpty())

            repo.clearAll()
            fixture.dispatcher.drain()
            val empty = repo.queue.value
            repo.clearAll()
            repo.clearFinished()
            repo.clearPending()
            repo.flush()
            assertSame(empty, repo.queue.value)
            assertEquals(0, fixture.dispatcher.pending)
            assertEquals(1, fixture.dao.writes.size)
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun batchCleanupTransformsEachSelectedItemOnceAndPreservesCompletedItems() = runBlocking {
        val fixture = Fixture((0 until 2000).map { index ->
            item("$index").copy(status = if (index % 2 == 0) QueueStatus.DONE else QueueStatus.READY)
        })
        try {
            val ids = (0 until 2000).map { "$it" }
            var transformed = 0
            fixture.repo.updateAll(ids + ids + "missing") {
                transformed++
                if (it.status == QueueStatus.DONE) it else it.copy(status = QueueStatus.FAILED, error = "interrupted")
            }
            val flush = async(start = CoroutineStart.UNDISPATCHED) { fixture.repo.flush() }
            fixture.dispatcher.drain()
            flush.await()
            assertEquals(2000, transformed)
            assertEquals(ids, fixture.repo.queue.value.map { it.id })
            assertEquals(1, fixture.dao.writes.size)
            assertEquals(1000, fixture.dao.writes.single().first.size)
            assertTrue(fixture.dao.rows.filter { it.status == QueueStatus.DONE }.all { it.error == null })
            assertTrue(fixture.dao.rows.filter { it.status == QueueStatus.FAILED }.all { it.error == "interrupted" })
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun removingConvertingItemsIsANoOp() = runBlocking {
        val fixture = Fixture(listOf(item("active")))
        try {
            assertNotNull(fixture.repo.claim("active", "batch"))
            fixture.dispatcher.drain()
            val active = fixture.repo.queue.value
            fixture.repo.removeIds(listOf("active"))
            fixture.repo.clearAll()
            fixture.repo.clearPending()
            fixture.repo.clearFinished()
            assertNull(fixture.repo.remove("active"))
            fixture.repo.flush()
            assertSame(active, fixture.repo.queue.value)
            assertEquals(0, fixture.dispatcher.pending)
            assertEquals(1, fixture.dao.writes.size)
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun revertedChangesCompleteFlushWithoutEmptyTransaction() = runBlocking {
        val fixture = Fixture(listOf(item("a")))
        try {
            fixture.repo.update("a") { it.copy(status = QueueStatus.READY) }
            fixture.repo.update("a") { it.copy(status = QueueStatus.PENDING) }
            val flush = async(start = CoroutineStart.UNDISPATCHED) { fixture.repo.flush() }
            assertFalse(flush.isCompleted)
            fixture.dispatcher.drain()
            flush.await()
            assertTrue(fixture.dao.writes.isEmpty())
            assertEquals(fixture.repo.queue.value, fixture.dao.rows)
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun flushWaitsForItsRevisionWhenChangesArriveDuringWrite() = runBlocking {
        val fixture = Fixture(listOf(item("a")))
        val firstWrite = CompletableDeferred<Unit>()
        val secondWrite = CompletableDeferred<Unit>()
        fixture.dao.beforeWrite = { number -> if (number == 1) firstWrite.await() else secondWrite.await() }
        try {
            fixture.repo.update("a") { it.copy(progress = 10) }
            fixture.dispatcher.drain() // First snapshot is now in flight.
            val firstFlush = async(start = CoroutineStart.UNDISPATCHED) { fixture.repo.flush() }
            fixture.repo.update("a") { it.copy(progress = 20) }
            val secondFlush = async(start = CoroutineStart.UNDISPATCHED) { fixture.repo.flush() }
            assertFalse(firstFlush.isCompleted)
            assertFalse(secondFlush.isCompleted)
            firstWrite.complete(Unit)
            fixture.dispatcher.drain()
            firstFlush.await()
            assertFalse(secondFlush.isCompleted)
            assertEquals(10, fixture.dao.rows.single().progress)
            secondWrite.complete(Unit)
            fixture.dispatcher.drain()
            secondFlush.await()
            assertEquals(20, fixture.dao.rows.single().progress)
            assertEquals(2, fixture.dao.writes.size)
            fixture.repo.flush()
            assertEquals(0, fixture.dispatcher.pending)
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun failedWriteDoesNotAdvanceBarrierAndFlushCanRetry() = runBlocking {
        val fixture = Fixture(listOf(item("a")))
        fixture.dao.beforeWrite = { error("disk unavailable") }
        try {
            fixture.repo.update("a") { it.copy(progress = 10) }
            val firstFlush = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { fixture.repo.flush() }
            }
            fixture.dispatcher.drain()
            assertEquals("disk unavailable", firstFlush.await().exceptionOrNull()?.message)
            assertEquals(0, fixture.dao.rows.single().progress)
            val failedAttempts = fixture.dao.writes.size
            fixture.dao.beforeWrite = {}
            val retry = async(start = CoroutineStart.UNDISPATCHED) { fixture.repo.flush() }
            assertFalse(retry.isCompleted)
            fixture.dispatcher.drain()
            retry.await()
            assertEquals(10, fixture.dao.rows.single().progress)
            assertEquals(failedAttempts + 1, fixture.dao.writes.size)
        } finally { fixture.close() }
    }

    private fun item(id: String) = QueueItem(id, "content://$id", "$id.jpg", 100, createdAt = 1)

    private class Fixture(initial: List<QueueItem>) {
        val dispatcher = QueuedDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val dao = RecordingTaskDao(initial)
        val repo = ConversionRepository(dao, scope)
        init { dispatcher.drain() }
        fun close() { scope.cancel(); dispatcher.drain() }
    }

    /** Explicitly drain repository work without sleeps or an additional coroutine-test dependency. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        val pending get() = tasks.size
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private class RecordingTaskDao(initial: List<QueueItem>) : TaskDao {
        var rows = initial
        val writes = mutableListOf<Pair<List<QueueItem>, List<String>>>()
        var beforeWrite: suspend (Int) -> Unit = {}
        override suspend fun all() = rows
        override suspend fun put(items: List<QueueItem>) {
            rows = (rows.associateBy { it.id } + items.associateBy { it.id }).values.toList()
        }
        override suspend fun removeIds(ids: List<String>) {
            val removed = ids.toSet()
            rows = rows.filterNot { it.id in removed }
        }
        override suspend fun applyChanges(changed: List<QueueItem>, removed: List<String>) {
            writes += changed to removed
            beforeWrite(writes.size)
            removeIds(removed)
            put(changed)
        }
        override suspend fun putBatch(batch: BatchEntity): Unit = error("Unused in queue tests")
        override suspend fun latestBatch(): BatchEntity? = error("Unused in queue tests")
        override suspend fun batch(id: String): BatchEntity? = error("Unused in queue tests")
        override suspend fun cancel(id: String): Unit = error("Unused in queue tests")
    }
}

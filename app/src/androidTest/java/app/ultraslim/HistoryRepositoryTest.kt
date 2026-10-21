package app.ultraslim

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.data.AppDatabase
import app.ultraslim.data.HistoryEntity
import app.ultraslim.data.HistoryPaths
import app.ultraslim.data.HistoryRepository
import app.ultraslim.data.HistoryStats
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repositoryScope: CoroutineScope
    private val aggregateQueries = AtomicInteger()

    @Before fun setUp() {
        aggregateQueries.set(0)
        repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).setQueryCallback(object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                if (sqlQuery.contains("as countDone") && sqlQuery.contains("SUM(inputBytes)")) {
                    aggregateQueries.incrementAndGet()
                }
            }
        }, Executor { it.run() }).build()
    }

    @After fun tearDown() = runBlocking {
        repositoryScope.coroutineContext[Job]!!.cancelAndJoin()
        database.close()
    }

    @Test fun pageAndCountWrappersKeepAllAndFilteredResultsConsistent() = runBlocking {
        val dao = database.historyDao()
        assertTrue(dao.flowPage(10, "").first().isEmpty())
        assertEquals(0, dao.flowCount("").first())
        dao.insert(row(1, saveState = "SAVED"))
        dao.insert(row(2, saveState = "FAILED"))
        dao.insert(row(3, saveState = "PENDING"))
        dao.insert(row(4, saveState = "SAVED"))
        dao.insert(row(5, saveState = "PENDING"))
        dao.insert(row(6, saveState = ""))

        assertEquals(listOf(6L, 5L, 4L), dao.flowPage(3, "").first().map { it.id })
        assertEquals(6, dao.flowCount("").first())
        assertEquals(listOf(4L, 1L), dao.flowPage(10, "SAVED").first().map { it.id })
        assertEquals(2, dao.flowCount("SAVED").first())
        assertEquals(listOf(5L), dao.flowPage(1, "PENDING").first().map { it.id })
        assertEquals(2, dao.flowCount("PENDING").first())
        assertEquals(listOf(2L), dao.flowPage(10, "FAILED").first().map { it.id })
        assertTrue(dao.flowPage(10, "missing").first().isEmpty())
        assertEquals(0, dao.flowCount("missing").first())
        assertTrue(dao.flowPage(0, "").first().isEmpty())
    }

    @Test fun suspendStatsIncludesOnlyDoneRowsAcrossPublicationStates() = runBlocking {
        val dao = database.historyDao()
        assertEquals(HistoryStats(), dao.stats())
        dao.insert(row(1, saveState = "PENDING", inputBytes = 100, outputBytes = 80, gainmap = true))
        dao.insert(row(2, saveState = "FAILED", inputBytes = 200, outputBytes = 250))
        dao.insert(row(3, status = HistoryEntity.STATUS_SKIPPED, inputBytes = 900, outputBytes = 1, gainmap = true))
        val expected = HistoryStats(countDone = 2, totalInputBytes = 300, totalOutputBytes = 330, gainmapCount = 1)
        assertEquals(expected, dao.stats())
        assertEquals(expected, dao.flowStats().first())
        assertEquals(-30L, dao.stats().totalSavedBytes)
        dao.deleteAll()
        assertEquals(HistoryStats(), dao.stats())
    }

    @Test fun startupPathsAndConditionalPromotionPreserveConcurrentPublication() = runBlocking {
        val dao = database.historyDao()
        val original = row(1).copy(inputUri = "input", outputUri = "cache/output", settingsSummary = "keep")
        dao.insert(original)
        assertEquals(listOf(HistoryPaths(1, "input", "cache/output")), dao.allPaths())
        assertEquals(1, dao.updateOutputPath(1, "cache/output", "files/output"))
        assertEquals(original.copy(outputUri = "files/output"), dao.byId(1))
        val published = original.copy(outputUri = "content://album/1", settingsSummary = "published")
        dao.update(published)
        assertEquals(0, dao.updateOutputPath(1, "files/output", "stale/output"))
        assertEquals(published, dao.byId(1))
        assertEquals(0, dao.updateOutputPath(999, "missing", "new"))
    }

    @Test fun sharedStatsSamplesBeforeSqlAndRefreshesAfterSubscriberRestart() = runBlocking {
        val dao = database.historyDao()
        dao.insert(row(1))
        val repository = HistoryRepository(database, repositoryScope)
        delay(600)
        assertEquals("No aggregate SQL without a subscriber", 0, aggregateQueries.get())

        val first = Channel<HistoryStats>(Channel.UNLIMITED)
        val second = Channel<HistoryStats>(Channel.UNLIMITED)
        val firstJob = launch(start = CoroutineStart.UNDISPATCHED) { repository.stats.collect { first.send(it) } }
        val secondJob = launch(start = CoroutineStart.UNDISPATCHED) { repository.stats.collect { second.send(it) } }
        try {
            assertEquals(HistoryStats(), first.receive())
            assertEquals(HistoryStats(), second.receive())
            val initial = HistoryStats(1, 100, 80, 0)
            first.awaitValue(initial)
            second.awaitValue(initial)
            assertEquals("Two subscribers share one initial SQL query", 1, aggregateQueries.get())

            val beforeBurst = aggregateQueries.get()
            val elapsed = measureTimeMillis {
                repeat(16) { index ->
                    dao.insert(row(index.toLong() + 2))
                    delay(25)
                }
                // The trailing write must arrive even after writes stop (no more invalidations).
                val final = HistoryStats(17, 1_700, 1_360, 0)
                first.awaitValue(final)
                second.awaitValue(final)
            }
            val queries = aggregateQueries.get() - beforeBurst
            assertTrue("Aggregate SQL is bounded by sampling ticks: $queries in ${elapsed}ms",
                queries <= elapsed / 500 + 2)

            val beforeMetadata = aggregateQueries.get()
            dao.noteReplacement(listOf(1L), "metadata only")
            withTimeout(5_000) {
                while (aggregateQueries.get() == beforeMetadata) delay(20)
            }
            delay(100)
            assertTrue("Unchanged stats should not emit", first.tryReceive().isFailure)
            assertTrue("Unchanged stats should not emit", second.tryReceive().isFailure)
        } finally {
            firstJob.cancelAndJoin()
            secondJob.cancelAndJoin()
        }

        // WhileSubscribed's grace period permits quick tab switches without restarting SQL.
        delay(5_600)
        val stoppedQueries = aggregateQueries.get()
        dao.insert(row(18))
        delay(650)
        assertEquals("No aggregate SQL once the last subscriber's grace period expires",
            stoppedQueries, aggregateQueries.get())
        withTimeout(5_000) {
            assertEquals(HistoryStats(18, 1_800, 1_440, 0), repository.stats.first { it.countDone == 18 })
        }
    }

    private suspend fun Channel<HistoryStats>.awaitValue(expected: HistoryStats) {
        withTimeout(5_000) {
            while (receive() != expected) { /* Skip intermediate aggregate values. */ }
        }
    }

    private fun row(
        id: Long,
        saveState: String = "SAVED",
        status: String = HistoryEntity.STATUS_DONE,
        inputBytes: Long = 100,
        outputBytes: Long = 80,
        gainmap: Boolean = false,
    ) = HistoryEntity(
        id = id, displayName = "$id.jpg", inputUri = "input/$id", outputUri = "output/$id",
        inputBytes = inputBytes, outputBytes = outputBytes, width = 1, height = 1,
        gainmapWidth = 0, gainmapHeight = 0, hadGainMap = gainmap,
        format = "JPEG", inputFormat = "JPEG", durationMs = 0.0,
        maxContentBoost = 1f, createdAt = id, status = status, saveState = saveState,
    )
}

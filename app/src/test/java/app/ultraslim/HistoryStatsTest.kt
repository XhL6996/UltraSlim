package app.ultraslim

import app.ultraslim.data.HistoryStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryStatsTest {

    @Test
    fun emptyStatsReturnZeroSafeDefaults() {
        val stats = HistoryStats()
        assertEquals(0, stats.countDone)
        assertEquals(0L, stats.totalSavedBytes)
        assertEquals(0f, stats.savedPercent, 0.001f)
        assertEquals(0L, stats.avgSavedBytes)
        assertEquals(0, stats.equivalentPhotosSaved)
    }

    @Test
    fun standardConversionCalculatesSavingsAndPhotoEquivalents() {
        val stats = HistoryStats(
            countDone = 10,
            totalInputBytes = 50_000_000L,
            totalOutputBytes = 15_000_000L,
            gainmapCount = 10,
        )
        assertEquals(35_000_000L, stats.totalSavedBytes)
        assertEquals(0.70f, stats.savedPercent, 0.001f)
        assertEquals(3_500_000L, stats.avgSavedBytes)
        assertEquals(7, stats.equivalentPhotosSaved)
    }

    @Test
    fun negativeSavingsHandledGracefully() {
        val stats = HistoryStats(
            countDone = 2,
            totalInputBytes = 10_000_000L,
            totalOutputBytes = 12_000_000L,
            gainmapCount = 0,
        )
        assertEquals(-2_000_000L, stats.totalSavedBytes)
        assertTrue(stats.savedPercent < 0f)
        assertEquals(0, stats.equivalentPhotosSaved)
    }
}

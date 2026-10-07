package app.ultraslim

import app.ultraslim.ui.history.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class HistorySelectionTest {
    @Test fun dateGroupingUsesLocalMidnightAndCrossesYear() {
        val zone = ZoneId.of("Asia/Shanghai")
        val date = LocalDate.of(2026, 1, 1)
        val range = historyDateRange(date, zone)
        assertEquals(date.minusDays(1), historyDate(range.first - 1, zone))
        assertEquals(date, historyDate(range.first, zone))
        assertEquals(date, historyDate(range.last, zone))
        assertEquals(date.plusDays(1), historyDate(range.last + 1, zone))
        assertEquals("昨天", historyDateTitle(date.minusDays(1), date))
    }
    @Test fun dateRangeHonorsDaylightSavingsInsteadOfAssuming24Hours() {
        val range = historyDateRange(LocalDate.of(2026, 3, 8), ZoneId.of("America/New_York"))
        assertEquals(23 * 3600_000L, range.last + 1 - range.first)
    }
}

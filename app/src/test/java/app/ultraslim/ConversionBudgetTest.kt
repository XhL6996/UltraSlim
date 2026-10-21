package app.ultraslim

import app.ultraslim.convert.ConversionBudget
import org.junit.Assert.*
import org.junit.Test

class ConversionBudgetTest {
    private val gib = 1024L * 1024 * 1024
    @Test fun lowMemoryAndSystemReservePreventAdmission() {
        assertFalse(ConversionBudget.canStart(4000, 3000, 1000, 750, true, 10_000_000, 8*gib, 16*gib, gib, true))
        assertFalse(ConversionBudget.canStart(4000, 3000, 1000, 750, true, 10_000_000, 2*gib, 16*gib, gib, false))
        assertTrue(ConversionBudget.canStart(4000, 3000, 1000, 750, true, 10_000_000, 8*gib, 16*gib, gib, false))
    }
    @Test fun largeGainmapCountsTowardsPeakAndInvalidDimensionsAreRejected() {
        assertTrue(ConversionBudget.canStart(4000, 3000, 1000, 750, true, 10_000_000, 4*gib, 8*gib, gib, false))
        assertFalse(ConversionBudget.canStart(4000, 3000, 10000, 10000, true, 10_000_000, 4*gib, 8*gib, gib, false))
        assertFalse(ConversionBudget.canStart(-1, -1, 0, 0, false, 0, 8*gib, 16*gib, gib, false))
        assertFalse(ConversionBudget.canStart(Int.MAX_VALUE, Int.MAX_VALUE, 0, 0, false, 0, 8*gib, 16*gib, gib, false))
    }
    @Test fun previewBudgetLeavesSystemReserveAndRejectsMemoryPressure() {
        assertEquals(0L, ConversionBudget.previewBytes(8*gib, 16*gib, gib, true))
        assertEquals(0L, ConversionBudget.previewBytes(2*gib, 16*gib, gib, false))
        assertEquals(0L, ConversionBudget.previewBytes(0, 16*gib, gib, false))
        assertEquals(0L, ConversionBudget.previewBytes(gib, 0, 0, false))
        assertEquals(8*gib / 3, ConversionBudget.previewBytes(8*gib, 16*gib, gib, false))
        assertEquals(gib / 10, ConversionBudget.previewBytes(gib, 2*gib, gib - gib / 10, false))
    }
    @Test fun encodingLeavesCpuHeadroomEvenOnManyCoreDevices() {
        assertEquals(1, ConversionBudget.threads(1))
        assertEquals(2, ConversionBudget.threads(4))
        assertEquals(4, ConversionBudget.threads(8))
        assertEquals(4, ConversionBudget.threads(32))
    }
}

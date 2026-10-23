package app.ultraslim

import app.ultraslim.convert.ConversionThermalPolicy
import app.ultraslim.convert.ConversionThermalState
import app.ultraslim.convert.ThermalPhase
import org.junit.Assert.*
import org.junit.Test

class ConversionThermalPolicyTest {
    @Test fun severeAndHigherNeverStartEncoding() {
        for (status in 0..2) assertFalse(ConversionThermalPolicy.requiresCooling(status))
        for (status in 3..6) assertTrue(ConversionThermalPolicy.requiresCooling(status))
        assertTrue(ConversionThermalPolicy.requiresCooling(7))
    }

    @Test fun retriesHaveAShortTotalBudgetAndCannotGrowWithoutBound() {
        val delays = (0 until ConversionThermalPolicy.MAX_RESUMES).map(ConversionThermalPolicy::retryDelaySeconds)
        assertEquals(listOf(5, 10, 15, 15, 15, 15, 15, 15), delays)
        assertEquals(105, delays.sum())
        assertEquals(105, ConversionThermalPolicy.remainingWaitSeconds(0))
        assertEquals(15, ConversionThermalPolicy.remainingWaitSeconds(7))
        assertEquals(0, ConversionThermalPolicy.remainingWaitSeconds(8))
        assertEquals(0, ConversionThermalPolicy.retryDelaySeconds(Int.MAX_VALUE))
        assertEquals(0, ConversionThermalPolicy.remainingWaitSeconds(Int.MAX_VALUE))
        assertTrue(ConversionThermalPolicy.CHECK_INTERVAL_SECONDS <= delays.min())
    }

    @Test fun feedbackNamesSystemStatusAndDistinguishesWaitingFromPaused() {
        val cooling = ConversionThermalState(3, ThermalPhase.COOLING, 105)
        assertTrue(cooling.message.contains("系统报告"))
        assertTrue(cooling.message.contains("等级 3"))
        assertTrue(cooling.message.contains("105 秒"))
        val paused = cooling.copy(phase = ThermalPhase.PAUSED, remainingSeconds = 0)
        assertTrue(paused.message.contains("已暂停"))
        assertTrue(paused.message.contains("点击继续"))
        assertFalse(paused.message.contains("自动"))
    }
}

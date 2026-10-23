package app.ultraslim.convert

enum class ThermalPhase { COOLING, PAUSED }

/** The status is Android's thermal-throttling level, not a measured temperature. */
data class ConversionThermalState(
    val status: Int,
    val phase: ThermalPhase,
    val remainingSeconds: Int,
) {
    val statusLabel: String get() = when (status) {
        0 -> "无热限制"
        1 -> "轻度热限制"
        2 -> "中度热限制"
        3 -> "重度热限制"
        4 -> "危急热状态"
        5 -> "紧急热状态"
        6 -> "即将因过热关机"
        else -> "未知热状态"
    } + "（等级 $status）"

    val message: String get() = when (phase) {
        ThermalPhase.COOLING -> "系统报告$statusLabel，等待降温；最多再等 $remainingSeconds 秒，未恢复将暂停"
        ThermalPhase.PAUSED -> "系统报告$statusLabel，转换已暂停；请降温后点击继续，原图与已完成结果保留"
    }
}

internal object ConversionThermalPolicy {
    // Keep the existing SEVERE safety threshold. Device-specific reports require diagnostics,
    // not bypassing protection or substituting battery temperature for the device thermal state.
    const val SEVERE = 3
    const val MAX_RESUMES = 8
    const val CHECK_INTERVAL_SECONDS = 5

    fun requiresCooling(status: Int) = status >= SEVERE

    /** Bounded exponential backoff: 5, 10, 15, ... seconds; at most 105 seconds total. */
    fun retryDelaySeconds(attempt: Int): Int =
        if (attempt >= MAX_RESUMES) 0 else (5 shl attempt.coerceIn(0, 2)).coerceAtMost(15)

    fun remainingWaitSeconds(attempt: Int): Int =
        (attempt.coerceAtLeast(0) until MAX_RESUMES).sumOf(::retryDelaySeconds)
}

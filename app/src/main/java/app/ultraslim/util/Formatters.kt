package app.ultraslim.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** "4.7 MB" / "512 KB" / "980 B" */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = "B"
    for (u in units) {
        if (value < 1024.0) break
        value /= 1024.0
        unit = u
    }
    return if (value >= 100.0) {
        String.format(Locale.US, "%.0f %s", value, unit)
    } else {
        String.format(Locale.US, "%.1f %s", value, unit)
    }
}

/** "−62%" when the output is smaller, "+13%" when it grew. */
fun formatSavedPercent(inputBytes: Long, outputBytes: Long): String {
    if (inputBytes <= 0L) return "—"
    val pct = ((1.0 - outputBytes.toDouble() / inputBytes.toDouble()) * 100.0).roundToInt()
    return if (pct >= 0) "−$pct%" else "+${-pct}%"
}

/** "350 毫秒" / "1.2 秒" */
fun formatDuration(durationMs: Double): String =
    if (durationMs < 1000.0) {
        "${durationMs.roundToLong()} 毫秒"
    } else {
        String.format(Locale.US, "%.1f 秒", durationMs / 1000.0)
    }

/** "4096 × 3072" */
fun formatResolution(width: Int, height: Int): String =
    if (width > 0 && height > 0) "$width × $height" else "未知"

/** "2.4×" */
fun formatBoost(boost: Float): String =
    if (boost > 0f) String.format(Locale.US, "%.1f×", boost) else "无"

/** 刚刚 / n 分钟前 / n 小时前 / n 天前 / yyyy年M月d日 */
fun formatRelativeTime(timeMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diff = (nowMs - timeMs).coerceAtLeast(0L)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "刚刚"
        diff < hour -> "${diff / minute} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        diff < 7 * day -> "${diff / day} 天前"
        else -> SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(timeMs))
    }
}

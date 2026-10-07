package app.ultraslim.ui.history

import app.ultraslim.data.HistoryEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal fun HistoryEntity.matchesHistoryFilter(filter: String): Boolean = when (filter) {
    "待保存" -> saveState == "PENDING"
    "已保存" -> saveState == "SAVED"
    "保存失败" -> saveState == "FAILED"
    else -> true
}

internal fun historyDate(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

internal fun historyDateRange(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): LongRange =
    date.atStartOfDay(zone).toInstant().toEpochMilli() until
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

internal fun historyDateTitle(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "今天"
    today.minusDays(1) -> "昨天"
    else -> if (date.year == today.year) "${date.monthValue}月${date.dayOfMonth}日"
        else "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
}

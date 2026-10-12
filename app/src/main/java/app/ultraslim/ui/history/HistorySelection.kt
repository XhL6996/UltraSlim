package app.ultraslim.ui.history

import app.ultraslim.data.HistoryEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal fun historyFilterSaveState(filter: String): String = when (filter) {
    "待保存" -> "PENDING"
    "已保存" -> "SAVED"
    "保存失败" -> "FAILED"
    else -> ""
}

internal fun HistoryEntity.matchesHistoryFilter(filter: String): Boolean =
    historyFilterSaveState(filter).let { it.isEmpty() || saveState == it }

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

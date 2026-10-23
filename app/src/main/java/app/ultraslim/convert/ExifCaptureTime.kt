package app.ultraslim.convert

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** EXIF without an offset has local wall time only; keep that limitation explicit. */
internal object ExifCaptureTime {
    private val format = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(original: String?, offset: String?, subsecond: String?,
              localZone: ZoneId = ZoneId.systemDefault()): Long? = runCatching {
        val date = LocalDateTime.parse(requireNotNull(original).trim(), format)
        val zone = offset?.trim()?.takeIf { it.isNotEmpty() }?.let(ZoneOffset::of) ?: localZone
        val millis = subsecond?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.take(3)?.padEnd(3, '0')?.toLong() ?: 0L
        (date.atZone(zone).toInstant().toEpochMilli() + millis).takeIf { it > 0 }
    }.getOrNull()
}

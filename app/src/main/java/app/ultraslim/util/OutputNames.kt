package app.ultraslim.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Human-readable stem plus a short stable token for crash-safe, duplicate-free publication. */
fun outputName(source: String, extension: String, taskId: String, createdAt: Long, motionPhoto: Boolean = false): String {
    val raw = source.substringBeforeLast('.', source).replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim(' ', '.')
    val stem = if(raw.isBlank() || raw.matches(Regex("[\\d_ -]+"))) {
        "Photo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(createdAt))
    } else raw.removeSuffix("_Slim").take(64)
    val token = java.security.MessageDigest.getInstance("SHA-256").digest(taskId.toByteArray())
        .take(4).joinToString("") { "%02x".format(it) }
    return "${stem}_Slim_${token}${if (motionPhoto) "MP" else ""}.${extension.lowercase(Locale.ROOT)}"
}

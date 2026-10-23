package app.ultraslim.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Human-readable stem plus a short stable token for crash-safe, duplicate-free publication. */
fun outputName(source: String, extension: String, taskId: String, createdAt: Long, motionPhoto: Boolean = false,
    keepFilename: Boolean = false): String {
    if (keepFilename) {
        val original = source.substringBeforeLast('.', source).replace(Regex("[\\\\/\\p{Cntrl}]"), "_")
        return "${original.ifBlank { "Photo" }}.${extension.lowercase(Locale.ROOT)}"
    }
    val raw = source.substringBeforeLast('.', source).replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim(' ', '.')
    val stem = if(raw.contains(Regex("(?i)screen[ _-]?(shot|capture)|截[屏图]")) || raw.isBlank() || raw.matches(Regex("[\\d_ -]+"))) {
        "Photo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(createdAt))
    } else raw.removeSuffix("_Slim").take(64)
    val token = java.security.MessageDigest.getInstance("SHA-256").digest(taskId.toByteArray())
        .take(4).joinToString("") { "%02x".format(it) }
    return "${stem}_Slim_${token}${if (motionPhoto) "MP" else ""}.${extension.lowercase(Locale.ROOT)}"
}

/** Use the output's SHA-256 only on collision. Compare case-insensitively for FAT/SAF providers. */
internal fun keptOutputName(desired: String, existing: Set<String>, sha256: String): String {
    val occupied = existing.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
    fun available(name: String) = name.lowercase(Locale.ROOT) !in occupied
    if (available(desired)) return desired
    val base = desired.substringBeforeLast('.', desired)
    val extension = desired.substringAfterLast('.', "")
    fun candidate(suffix: String): String {
        val tail = "_$suffix" + if (extension.isEmpty()) "" else ".$extension"
        // A suffix must not push an otherwise valid filename over the filesystem's 255-byte limit.
        var stem = base
        while ((stem + tail).toByteArray(Charsets.UTF_8).size > 255 && stem.isNotEmpty()) {
            stem = stem.dropLast(if (stem.last().isLowSurrogate() && stem.length > 1) 2 else 1)
        }
        return stem + tail
    }
    for (length in 8..64 step 4) {
        val name = candidate(sha256.take(length))
        if (available(name)) return name
    }
    var index = 2
    while (true) {
        val name = candidate("${sha256}_$index")
        if (available(name)) return name
        index++
    }
}

/** A provider may see collisions hidden from our query, or another app may create a file meanwhile. */
internal fun confirmKeptOutputName(desired: String, chosen: String, existing: Set<String>, sha256: String,
    readName: () -> String?, rename: (String) -> Unit) {
    val occupied = existing.toMutableSet()
    var candidate = chosen
    repeat(32) {
        val actual = readName() ?: error("无法确认输出文件名")
        if (actual == candidate) return
        occupied += desired
        occupied += candidate
        occupied += actual
        candidate = keptOutputName(desired, occupied, sha256)
        // A rename can itself collide with a hidden file. Try a longer hash on the next pass.
        runCatching { rename(candidate) }
    }
    check(readName() == candidate) { "无法保留输出文件名，成品仍保留在应用内" }
}

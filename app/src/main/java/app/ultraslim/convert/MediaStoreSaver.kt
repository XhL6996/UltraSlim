package app.ultraslim.convert

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import app.ultraslim.model.OutputFormat
import java.io.File
import java.io.IOException

/**
 * Names already present in Pictures/UltraSlim for one conversion run.
 * Parallel saves share one instance so each file is queried once.
 */
class AlbumNames(context: Context) {
    private val names = queryAlbumNames(context.contentResolver).toMutableSet()

    fun reserve(desired: String): String = synchronized(names) {
        val unique = disambiguateName(desired, names)
        names += unique
        unique
    }

    fun release(name: String) {
        synchronized(names) { names -= name }
    }
}

private fun disambiguateName(desired: String, existing: Set<String>): String {
    if (desired !in existing) return desired
    val base = desired.substringBeforeLast('.', desired)
    val ext = desired.substringAfterLast('.', "")
    var index = 2
    while (true) {
        val candidate = if (ext.isEmpty()) "$base ($index)" else "$base ($index).$ext"
        if (candidate !in existing) return candidate
        index++
    }
}

private fun queryAlbumNames(resolver: ContentResolver): Set<String> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptySet()
    val names = mutableSetOf<String>()
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    runCatching {
        resolver.query(
            collection,
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%UltraSlim%"),
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            if (index >= 0) while (cursor.moveToNext()) names += cursor.getString(index)
        }
    }
    return names
}

/**
 * Publishes a transcoded file into a chosen MediaStore album (default Pictures/UltraSlim),
 * using the IS_PENDING flow on Android 10+.
 *
 * Returns the content uri of the new entry, or null on failure. On Android 9
 * and below saving is not attempted (WRITE_EXTERNAL_STORAGE is not declared).
 */
object MediaStoreSaver {
    private val STABLE_NAME_REGEX = Regex(".*_Slim_[0-9a-f]{8}(?:MP)?\\.[^.]+")

    fun save(
        context: Context,
        sourceFile: File,
        displayName: String,
        format: OutputFormat,
        names: AlbumNames? = null,
        taskId: String? = null,
        relativePath: String = "Pictures/UltraSlim/",
        volume: String = MediaStore.VOLUME_EXTERNAL_PRIMARY,
        dateTaken: Long? = null,
        relatedUri: Uri? = null,
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (!sourceFile.isFile) return null

        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(volume)
        // Stable name enables recovery if the process died after MediaStore publication.
        val desired = if (taskId != null && !displayName.matches(STABLE_NAME_REGEX)) {
            // Recover records created by earlier app versions with their original stable name.
            displayName.substringBeforeLast('.') + "_" + taskId + "." + format.extension
        } else displayName
        if (taskId != null) {
            resolver.query(collection,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.IS_PENDING),
                "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?",
                arrayOf(desired, relativePath), null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val found = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
                    if (cursor.getInt(1) == 0) return found
                    resolver.delete(found, null, null)
                }
            }
        }
        val chosen = if (taskId != null) desired else names?.reserve(desired) ?: disambiguateName(desired, queryAlbumNames(resolver))

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, chosen)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            dateTaken?.takeIf { it > 0 }?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = runCatching {
            if (Build.VERSION.SDK_INT >= 30 && relatedUri != null) resolver.insert(collection, values,
                android.os.Bundle().apply { putParcelable(MediaStore.QUERY_ARG_RELATED_URI, relatedUri) })
            else resolver.insert(collection, values)
        }.getOrNull()
        if (uri == null) {
            names?.release(chosen)
            return null
        }

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("openOutputStream returned null for $uri")

            // DATE_TAKEN is scanner-controlled on current Android. Keep the real file timestamp too,
            // so pictures without EXIF dates retain chronology when the provider indexes them.
            preserveFileTime(context, uri, dateTaken)
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            dateTaken?.takeIf { it > 0 }?.let { values.put(MediaStore.Images.Media.DATE_TAKEN, it) }
            check(resolver.update(uri, values, null, null) == 1) { "无法发布相册文件" }
            uri
        } catch (e: Exception) {
            names?.release(chosen)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    internal fun preserveFileTime(context: Context, uri: Uri, timestamp: Long?) {
        if (timestamp == null || timestamp <= 0) return
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                // Resolve the descriptor we own; opening /proc/self/fd directly for utime is denied by FUSE.
                val path = android.system.Os.readlink("/proc/self/fd/${descriptor.fd}")
                check(File(path).setLastModified(timestamp)) { "Provider declined timestamp update" }
            }
        }.onFailure { android.util.Log.w("MediaStoreSaver", "Original file timestamp could not be preserved", it) }
    }

}

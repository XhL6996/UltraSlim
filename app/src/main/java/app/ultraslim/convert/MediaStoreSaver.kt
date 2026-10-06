package app.ultraslim.convert

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
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
 * Copies a transcoded file from the app cache into MediaStore.Images under
 * `Pictures/UltraSlim`, using the IS_PENDING flow on Android 10+.
 *
 * Returns the content uri of the new entry, or null on failure. On Android 9
 * and below saving is not attempted (WRITE_EXTERNAL_STORAGE is not declared).
 */
object MediaStoreSaver {

    fun save(
        context: Context,
        sourceFile: File,
        displayName: String,
        format: OutputFormat,
        names: AlbumNames? = null,
        taskId: String? = null,
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (!sourceFile.isFile) return null

        val resolver = context.contentResolver
        // Stable name enables recovery if the process died after MediaStore publication.
        val desired = if (taskId != null && !displayName.matches(Regex(".*_Slim_[0-9a-f]{8}\\.[^.]+"))) {
            // Recover records created by earlier app versions with their original stable name.
            displayName.substringBeforeLast('.') + "_" + taskId + "." + format.extension
        } else displayName
        if (taskId != null) {
            resolver.query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.IS_PENDING),
                "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?",
                arrayOf(desired, "Pictures/UltraSlim/"), null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val found = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cursor.getLong(0))
                    if (cursor.getInt(1) == 0) return found
                    resolver.delete(found, null, null)
                }
            }
        }
        val chosen = if (taskId != null) desired else names?.reserve(desired) ?: disambiguateName(desired, queryAlbumNames(resolver))

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, chosen)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/UltraSlim/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
        if (uri == null) {
            names?.release(chosen)
            return null
        }

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("openOutputStream returned null for $uri")

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1) { "无法发布相册文件" }
            uri
        } catch (e: Exception) {
            names?.release(chosen)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}

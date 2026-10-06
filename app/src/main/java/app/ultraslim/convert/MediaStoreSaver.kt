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
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (!sourceFile.isFile) return null

        val resolver = context.contentResolver
        val chosen = names?.reserve(displayName) ?: disambiguateName(displayName, queryAlbumNames(resolver))

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
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            names?.release(chosen)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}

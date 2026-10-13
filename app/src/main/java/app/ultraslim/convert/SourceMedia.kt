package app.ultraslim.convert

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** Publication metadata is read from the original, not from conversion/import wall-clock time. */
internal data class SourceMedia(val volume: String, val relativePath: String, val dateTaken: Long?) {
    companion object {
        fun captureTime(context: Context, source: Uri): Long? {
            for (column in listOf(MediaStore.Images.Media.DATE_TAKEN, android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)) {
                val value = runCatching { context.contentResolver.query(source, arrayOf(column), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { time -> time > 0 } else null
                } }.getOrNull()
                if (value != null) return value
            }
            return null
        }

        fun read(context: Context, source: Uri): SourceMedia? {
            if (Build.VERSION.SDK_INT < 29) return null
            val uri = MediaStoreTrashHelper.resolveToMediaStoreImageUri(context, source) ?: return null
            return runCatching {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.VOLUME_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_ADDED), null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@use null
                    val volume = c.getString(0) ?: return@use null
                    val path = c.getString(1) ?: return@use null
                    if (volume in setOf("external", "internal") || path.isBlank() || path.startsWith('/') ||
                        path.split('/').any { it == ".." || it == "." }) return@use null
                    val date = c.getLong(2).takeIf { it > 0 } ?: c.getLong(3).takeIf { it > 0 }?.times(1000)
                        ?: c.getLong(4).takeIf { it > 0 }?.times(1000)
                    SourceMedia(volume, path.trimEnd('/') + "/", date)
                }
            }.getOrNull()
        }
    }
}

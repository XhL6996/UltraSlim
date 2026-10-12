package app.ultraslim.convert

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

object MediaStoreTrashHelper {

    /** Returns true if the device supports MediaStore trash operations (Android 11+ / API 30+). */
    val isTrashSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Resolves an arbitrary image Uri (MediaStore, DocumentProvider, or PhotoPicker)
     * to a canonical MediaStore.Images.Media Uri if managed by MediaStore.
     */
    fun resolveToMediaStoreImageUri(context: Context, uri: Uri): Uri? {
        if (uri.scheme != "content") return null

        // Preserve the actual volume. A numeric picker id is not a MediaStore id.
        if (uri.authority == MediaStore.AUTHORITY &&
            uri.pathSegments.size == 4 && uri.pathSegments[1] == "images" &&
            uri.pathSegments[2] == "media" && uri.lastPathSegment?.toLongOrNull() != null) return uri
        if (Build.VERSION.SDK_INT >= 29 && uri.authority == "com.android.providers.media.documents") {
            val mapped = runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
            if (mapped != null && mapped != uri) return resolveToMediaStoreImageUri(context, mapped)
        }

        return null
    }

    /**
     * Creates an IntentSender that asks the user to move the given photos to the system trash.
     * Returns null if unsupported (API < 30) or no valid MediaStore Uris found.
     */
    fun createTrashIntentSender(context: Context, uris: Collection<Uri>): IntentSender? {
        if (!isTrashSupported) return null
        val resolved = uris.mapNotNull { resolveToMediaStoreImageUri(context, it) }.distinct()
        if (resolved.isEmpty()) return null

        return runCatching {
            MediaStore.createTrashRequest(context.contentResolver, resolved, true).intentSender
        }.getOrNull()
    }

    /**
     * Checks if the given original image is currently in the system trash.
     */
    fun isOriginalTrashed(context: Context, uri: Uri): Boolean {
        if (!isTrashSupported) return false
        val target = resolveToMediaStoreImageUri(context, uri) ?: return false
        return runCatching {
            context.contentResolver.query(
                target,
                arrayOf(MediaStore.MediaColumns.IS_TRASHED),
                android.os.Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) },
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                    if (col >= 0) cursor.getInt(col) == 1 else false
                } else false
            } ?: false
        }.getOrDefault(false)
    }

    /**
     * Checks if the original image still exists and is accessible.
     */
    fun isOriginalAccessible(context: Context, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }
}

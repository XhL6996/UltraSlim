package app.ultraslim.convert

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
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

        val auth = uri.authority ?: return null

        // 1. Direct MediaStore URI
        if (auth == MediaStore.AUTHORITY || auth == "media") {
            val path = uri.path ?: ""
            if (path.contains("/images/media/")) {
                val id = uri.lastPathSegment?.toLongOrNull()
                if (id != null) {
                    return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }
            }
            return uri
        }

        // 2. SAF Document URI from MediaDocumentsProvider
        if (auth == "com.android.providers.media.documents") {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("image:")) {
                    val id = docId.substringAfter("image:").toLongOrNull()
                    if (id != null) {
                        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    }
                }
            }
        }

        // 3. Photo Picker URI (com.android.providers.media.photopicker or similar)
        if (auth.contains("photopicker") || uri.path?.contains("picker") == true) {
            val lastId = uri.lastPathSegment?.toLongOrNull()
            if (lastId != null) {
                val candidate = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, lastId)
                val exists = runCatching {
                    context.contentResolver.query(
                        candidate,
                        arrayOf(MediaStore.Images.Media._ID),
                        null,
                        null,
                        null,
                    )?.use { it.moveToFirst() }
                }.getOrNull() == true
                if (exists) return candidate
            }
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
                null,
                null,
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

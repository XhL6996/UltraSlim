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
import java.io.InputStream
import java.security.MessageDigest
import app.ultraslim.util.keptOutputName
import app.ultraslim.util.confirmKeptOutputName

internal fun disambiguateName(desired: String, existing: Set<String>): String {
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
        taskId: String? = null,
        relativePath: String = "Pictures/UltraSlim/",
        volume: String = MediaStore.VOLUME_EXTERNAL_PRIMARY,
        dateTaken: Long? = null,
        relatedUri: Uri? = null,
        expectedFingerprint: Fingerprint? = null,
        keepFilename: Boolean = false,
        fileModifiedTime: Long? = dateTaken,
        onFailure: (Exception) -> Unit = {},
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (!sourceFile.isFile) return null

        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(volume)
        // Stable name enables recovery if the process died after MediaStore publication.
        val desired = if (!keepFilename && taskId != null && !displayName.matches(STABLE_NAME_REGEX)) {
            // Recover records created by earlier app versions with their original stable name.
            displayName.substringBeforeLast('.') + "_" + taskId + "." + format.extension
        } else displayName
        // A name is only a recovery hint. Never delete or adopt an existing item
        // (including a pending one) unless its readable bytes match our output.
        val expected = expectedFingerprint
            ?: runCatching { sourceFile.inputStream().use(::fingerprint) }.getOrNull() ?: return null
        if (expected.bytes == 0L) return null
        val receipt = if (keepFilename) PublicationReceipt(sourceFile, "gallery:$volume:$relativePath:$desired") else null
        receipt?.recover(context, expected)?.let { found ->
            return runCatching {
                check(resolver.update(found, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
                preserveCaptureTime(context, found, dateTaken)
                requireFileTime(context, found, fileModifiedTime)
                found
            }.onFailure { if (it is Exception) onFailure(it) }.getOrNull()
        }
        val existingNames = mutableSetOf<String>()
        var recovered: Uri? = null
        val base = desired.substringBeforeLast('.', desired)
        val extension = desired.substringAfterLast('.', "")
        fun escapeLike(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val numbered = escapeLike(base) + " (%)" + if (extension.isEmpty()) "" else "." + escapeLike(extension)
        val queried = runCatching {
            resolver.query(collection,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.IS_PENDING,
                    MediaStore.Images.Media.DISPLAY_NAME),
                if (keepFilename) "${MediaStore.Images.Media.RELATIVE_PATH} = ?" else
                    "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
                    "(${MediaStore.Images.Media.DISPLAY_NAME} = ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? ESCAPE '\\')",
                if (keepFilename) arrayOf(relativePath) else arrayOf(relativePath, desired, numbered), "${MediaStore.Images.Media._ID} ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    existingNames += cursor.getString(2)
                    if (!keepFilename && taskId != null && recovered == null && cursor.getInt(1) == 0) {
                        val found = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
                        if (matches(resolver, found, expected)) recovered = found
                    }
                }
            } ?: throw IOException("无法检查相册中已有文件")
        }.isSuccess
        if (!queried) return null
        recovered?.let { return runCatching {
            preserveCaptureTime(context, it, dateTaken)
            requireFileTime(context, it, fileModifiedTime)
            it
        }
            .onFailure { error -> if (error is Exception) onFailure(error) }.getOrNull() }
        val chosen = if (keepFilename) keptOutputName(desired, existingNames, expected.sha256) else disambiguateName(desired, existingNames)

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
        if (uri == null) return null

        return try {
            if (keepFilename) confirmKeptOutputName(desired, chosen, existingNames, expected.sha256,
                readName = { displayName(context, uri) },
                rename = { renamed ->
                    check(resolver.update(uri, ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, renamed)
                    }, null, null) == 1) { "无法设置输出文件名" }
                })
            receipt?.record(uri)
            // 写入时同步计算摘要：数据刚流经内存，不必再多一次整读就能确认写盘内容。
            // 短写或内容不符在这里失败，发布后的整读校验仍是最终安全门。
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            out.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            written += count
                        }
                    }
                }
            } ?: throw IOException("openOutputStream returned null for $uri")
            check(written == expected.bytes && digest.digest().joinToString("") { "%02x".format(it) } == expected.sha256) {
                "相册副本写入校验失败"
            }
            // Capture time and file modification time are different. Preserve both when known;
            // never rewrite EXIF or DATE_ADDED to force a gallery's sorting preference.
            requireFileTime(context, uri, fileModifiedTime)
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            dateTaken?.takeIf { it > 0 }?.let { values.put(MediaStore.Images.Media.DATE_TAKEN, it) }
            check(resolver.update(uri, values, null, null) == 1) { "无法发布相册文件" }
            // Publishing may run the scanner, which can replace DATE_TAKEN when it cannot read
            // this output's EXIF. Reapply the known source date after publication, best effort.
            preserveCaptureTime(context, uri, dateTaken)
            // Check actual mtime after publication and capture-index repairs as well.
            requireFileTime(context, uri, fileModifiedTime)
            check(matches(resolver, uri, expected)) { "相册副本发布校验失败" }
            uri
        } catch (e: Exception) {
            android.util.Log.w("UltraSlimOutput", "Gallery publication failed", e)
            onFailure(e)
            runCatching { resolver.delete(uri, null, null) }
            receipt?.delete()
            null
        }
    }

    data class Fingerprint(val bytes: Long, val sha256: String)

    internal fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null
        }

    /** One read of the private result; callers pass it down to avoid re-hashing the same file. */
    internal fun fingerprintOf(file: File): Fingerprint? =
        runCatching { file.inputStream().use(::fingerprint) }.getOrNull()

    private fun fingerprint(input: InputStream): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) { digest.update(buffer, 0, count); bytes += count }
        }
        return Fingerprint(bytes, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun matches(resolver: ContentResolver, uri: Uri, expected: Fingerprint): Boolean = runCatching {
        resolver.openInputStream(uri)?.use(::fingerprint) == expected
    }.getOrDefault(false)

    /** Final gate before the private result can be discarded by OutputStore. */
    internal fun matchesAlbumCopy(context: Context, uri: Uri, expected: Fingerprint?): Boolean {
        val known = expected ?: return false
        return known.bytes > 0 && matches(context.contentResolver, uri, known)
    }

    internal fun preserveCaptureTime(context: Context, uri: Uri, timestamp: Long?) {
        if (timestamp == null || timestamp <= 0) return
        runCatching {
            val current = context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            }
            if (current != timestamp) context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.DATE_TAKEN, timestamp)
            }, null, null)
        }.onFailure { android.util.Log.w("MediaStoreSaver", "Source capture time could not be indexed", it) }
    }

    /** SAF has no writable mtime column. Try the granted descriptor, then the local FUSE path. */
    internal fun requireFileTime(context: Context, uri: Uri, timestamp: Long?) {
        if (timestamp == null) return
        OutputFileTime.require(context, uri, timestamp)
    }

}

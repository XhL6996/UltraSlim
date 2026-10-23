package app.ultraslim.convert

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import app.ultraslim.bridge.UltraHdrBridge
import java.io.File
import java.io.IOException

/** Timestamp changes stay limited to the output URI created by our publisher. */
object OutputFileTime {
    internal fun localOutputFile(context: Context, uri: Uri): File? {
        if (Build.VERSION.SDK_INT < 30) return null
        val (volumeId, relative) = when {
            uri.authority == "com.android.externalstorage.documents" && DocumentsContract.isDocumentUri(context, uri) -> {
                val id = DocumentsContract.getDocumentId(uri)
                id.substringBefore(':') to id.substringAfter(':', "")
            }
            uri.authority == "media" -> context.contentResolver.query(uri, arrayOf(
                MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (!it.moveToFirst()) return null
                val volume = it.getString(0) ?: return null
                val directory = it.getString(1) ?: return null
                val name = it.getString(2) ?: return null
                if (name.isBlank() || name.contains('/') || name.contains('\\')) return null
                (if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) "primary" else volume) to
                    (directory.trimEnd('/') + "/" + name)
            } ?: return null
            else -> return null
        }
        if (relative.isBlank() || relative.startsWith('/') || relative.split('/').any { it == ".." || it == "." }) return null
        val volume = context.getSystemService(StorageManager::class.java).storageVolumes.firstOrNull {
            if (volumeId.equals("primary", true)) it.isPrimary else it.uuid.equals(volumeId, true)
        } ?: return null
        val root = volume.directory?.canonicalFile ?: return null
        val file = File(root, relative).canonicalFile
        return file.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    internal fun require(context: Context, uri: Uri, timestamp: Long) {
        check(timestamp > 0) { "无法读取原图修改时间" }
        if (actualTime(context, uri) == timestamp) return
        var descriptorFailure: Exception? = null
        var timeMismatch = false
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                if (!UltraHdrBridge.nativeSetModifiedTime(descriptor.fd, timestamp)) {
                    // Provider fds can point into /mnt/media_rw, which is inaccessible to an app.
                    // The app-visible /storage path is a separate route below.
                    val path = Os.readlink("/proc/self/fd/${descriptor.fd}")
                    if (!File(path).setLastModified(timestamp)) throw IOException("文件提供者拒绝修改时间")
                }
            } ?: throw IOException("无法打开输出文件以修改时间")
            // Verify the actual file after closing the write fd, not a stale provider metadata index.
            if (actualTime(context, uri) == timestamp) {
                android.util.Log.i("UltraSlimFileTime", "mtime preserved through document descriptor")
                return
            }
            timeMismatch = true
        } catch (e: Exception) {
            descriptorFailure = e
            android.util.Log.w("UltraSlimFileTime", "Document descriptor timestamp route failed", e)
        }

        // The document provider and MediaProvider can expose different filesystem views.
        // getMediaUri transfers only our existing SAF access; it requests no broader permission.
        if (Build.VERSION.SDK_INT >= 29 && uri.authority == "com.android.externalstorage.documents") {
            try {
                val media = MediaStore.getMediaUri(context, uri)
                if (media != null) {
                    context.contentResolver.openFileDescriptor(media, "rw")?.use { descriptor ->
                        check(UltraHdrBridge.nativeSetModifiedTime(descriptor.fd, timestamp)) {
                            "MediaStore 文件描述符拒绝修改时间"
                        }
                    } ?: throw IOException("无法打开对应的 MediaStore 文件")
                    if (actualTime(context, uri) == timestamp) {
                        android.util.Log.i("UltraSlimFileTime", "mtime preserved through MediaStore descriptor")
                        return
                    }
                    timeMismatch = true
                } else android.util.Log.i("UltraSlimFileTime", "Output not yet indexed in MediaStore")
            } catch (e: Exception) {
                descriptorFailure = e
                android.util.Log.w("UltraSlimFileTime", "MediaStore descriptor timestamp route failed", e)
            }
        }

        val local = runCatching { localOutputFile(context, uri) }.getOrNull()
        // The OS decides whether this single output path is writable with the current grants.
        if (local != null) {
            try {
                val before = Os.stat(local.path)
                check(android.system.OsConstants.S_ISREG(before.st_mode)) { "输出不是普通文件" }
                // Return actual EPERM/EACCES instead of File.setLastModified's uninformative false.
                val errno = UltraHdrBridge.nativeSetPathModifiedTime(local.path.toByteArray(Charsets.UTF_8), timestamp)
                if (errno != 0) throw android.system.ErrnoException("utimensat", errno)
                check(local.lastModified() == timestamp) { "输出文件系统无法保留此修改时间精度" }
                check(actualTime(context, uri) == timestamp) { "输出文件描述符的修改时间与本地路径不一致" }
                android.util.Log.i("UltraSlimFileTime", "mtime preserved through local path")
                return
            } catch (e: Exception) {
                descriptorFailure = e
                android.util.Log.w("UltraSlimFileTime", "Local path timestamp route failed", e)
            }
        }
        android.util.Log.w("UltraSlimFileTime", "Could not preserve output mtime", descriptorFailure)
        val message = when {
            timeMismatch -> "输出文件修改时间与原图不一致，提供者可能重设时间或文件系统不支持原图的时间精度"
            local != null -> "无法保留修改时间，请检查输出目录是否可写及文件系统时间精度"
            else -> "输出提供者拒绝修改时间或无法保留原图的时间精度，请选择本地存储目录"
        }
        val errno = (descriptorFailure as? android.system.ErrnoException)?.let {
            "（${Os.strerror(it.errno)} / errno=${it.errno}）"
        }.orEmpty()
        throw IOException(message + errno, descriptorFailure)
    }

    private fun actualTime(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use {
            val stat = Os.fstat(it.fileDescriptor)
            if (!android.system.OsConstants.S_ISREG(stat.st_mode)) return@use null
            if (Build.VERSION.SDK_INT >= 27) stat.st_mtim.let { time ->
                time.tv_sec * 1000 + time.tv_nsec / 1_000_000
            } else File(Os.readlink("/proc/self/fd/${it.fd}")).lastModified()
        }
    }.getOrNull()
}

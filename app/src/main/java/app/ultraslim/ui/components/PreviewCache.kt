package app.ultraslim.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import app.ultraslim.bridge.UltraHdrBridge
import java.io.File
import java.security.MessageDigest

/**
 * JPEG bytes from [UltraHdrBridge.nativeDecodePreview], kept in memory (~32MB)
 * and under cacheDir/previews (newest 200 files).
 */
internal object PreviewCache {
    private const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_DISK_FILES = 200

    private val memory = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    private val diskLock = Any()

    fun get(uriString: String, maxDim: Int): Bitmap? = memory.get(cacheKey(uriString, maxDim))

    fun load(context: Context, uriString: String, maxDim: Int): Bitmap? {
        val key = cacheKey(uriString, maxDim)
        memory.get(key)?.let { return it }
        val dir = File(context.cacheDir, "previews")
        val file = File(dir, "$key.jpg")
        synchronized(diskLock) {
            memory.get(key)?.let { return it }
            if (file.isFile && file.length() > 0L) {
                val cached = BitmapFactory.decodeFile(file.absolutePath)
                if (cached != null) {
                    file.setLastModified(System.currentTimeMillis())
                    memory.put(key, cached)
                    return cached
                }
                file.delete()
            }
        }
        val bytes = decode(context, uriString, maxDim) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        synchronized(diskLock) {
            memory.get(key)?.let { return it }
            dir.mkdirs()
            runCatching { file.writeBytes(bytes) }
            trim(dir)
            memory.put(key, bitmap)
        }
        return bitmap
    }

    private fun decode(context: Context, uriString: String, maxDim: Int): ByteArray? = runCatching {
        if (uriString.startsWith("content://")) {
            context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")
                ?.use { UltraHdrBridge.nativeDecodePreview(it.fd, maxDim) }
        } else {
            ParcelFileDescriptor.open(File(uriString), ParcelFileDescriptor.MODE_READ_ONLY)
                .use { UltraHdrBridge.nativeDecodePreview(it.fd, maxDim) }
        }
    }.getOrNull()

    private fun trim(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_DISK_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_DISK_FILES)
            .forEach { it.delete() }
    }

    private fun cacheKey(uriString: String, maxDim: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$maxDim\n$uriString".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

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
    private val requestLocks = Array(32) { Any() }
    private val decodeSlots = java.util.concurrent.Semaphore(1)

    fun load(context: Context, uriString: String, maxDim: Int): Bitmap? {
        val version = getVersion(context, uriString) ?: return null
        val key = cacheKey("$uriString:$version:v4", maxDim)

        // 1. Fast-path: In-memory cache hit (no locks, no semaphore)
        memory.get(key)?.let { return it }

        // 2. Disk cache check (only diskLock, does not block on native decode semaphore)
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

        // 3. Heavy native decode: serialized via per-URI requestLocks and global decodeSlots
        return synchronized(requestLocks[(uriString.hashCode() and Int.MAX_VALUE) % requestLocks.size]) {
            memory.get(key)?.let { return@synchronized it }
            decodeSlots.acquire()
            try {
                val bytes = decode(context, uriString, maxDim) ?: return@synchronized null
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@synchronized null
                synchronized(diskLock) {
                    memory.get(key)?.let { return@synchronized it }
                    dir.mkdirs()
                    runCatching { file.writeBytes(bytes) }
                    trim(dir)
                    memory.put(key, bitmap)
                }
                bitmap
            } finally {
                decodeSlots.release()
            }
        }
    }

    private fun getVersion(context: Context, uriString: String): String? = runCatching {
        if (uriString.startsWith("content://")) {
            val uri = Uri.parse(uriString)
            var modified = 0L
            var size = 0L
            var queried = false
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.MediaStore.MediaColumns.DATE_MODIFIED, android.provider.MediaStore.MediaColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    queried = true
                    val modIdx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATE_MODIFIED)
                    val sizeIdx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.SIZE)
                    if (modIdx >= 0) modified = cursor.getLong(modIdx)
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
            if (!queried || (size == 0L && modified == 0L)) {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { "${it.statSize}:$modified" } ?: return null
            } else {
                "$size:$modified"
            }
        } else {
            val source = File(uriString)
            if (!source.isFile) return null
            "${source.length()}:${source.lastModified()}"
        }
    }.getOrNull()

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
        var bytes = files.sumOf { it.length() }
        var count = files.size
        files.sortedBy { it.lastModified() }.forEach { file ->
            if(count > MAX_DISK_FILES || bytes > 128L * 1024 * 1024) { val n=file.length(); if(file.delete()){bytes-=n;count--;} }
        }
    }

    private fun cacheKey(uriString: String, maxDim: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$maxDim\n$uriString".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

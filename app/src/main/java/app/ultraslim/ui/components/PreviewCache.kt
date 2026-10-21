package app.ultraslim.ui.components

import android.app.ActivityManager
import android.content.Context
import app.ultraslim.convert.ConversionBudget
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.convert.ImageWork
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.withLock

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
    private val requestLocks = Array(32) { kotlinx.coroutines.sync.Mutex() }
    // 档位必须是真实存在的档：详情页放大请求 2048，缺档会被 firstOrNull 抬到 4096，
    // 而 4096 长边 RGBA 超过 MAX_BYTES，LruCache.put 单条超限会先清空整表再丢弃自己。
    private val resolutions = listOf(128, 384, 512, 1600, 2048, 4096)

    // content URI 的 version（size:mtime）每次都要一次 provider round-trip；
    // 列表滚动是秒级窗口，2 秒 TTL 足以消掉重复查询。
    private const val VERSION_TTL_MS = 2000L
    private val versionCache = object : LruCache<String, Pair<String, Long>>(512) {}

    private fun versionOf(context: Context, uriString: String): String? {
        if (uriString.startsWith("content://")) {
            versionCache.get(uriString)?.let { (cached, at) ->
                if (System.currentTimeMillis() - at < VERSION_TTL_MS) return cached
            }
        }
        val version = getVersion(context, uriString) ?: return null
        if (uriString.startsWith("content://")) versionCache.put(uriString, version to System.currentTimeMillis())
        return version
    }

    suspend fun load(context: Context, uriString: String, requestedDim: Int): Bitmap? {
        val maxDim = resolutions.firstOrNull { it >= requestedDim } ?: 4096
        val version = versionOf(context, uriString) ?: return null
        val key = cacheKey("$uriString:$version:v5", maxDim)

        // 1. Fast-path: In-memory cache hit (no locks, no semaphore)
        memory.get(key)?.let { return it }

        // 2. Disk cache：锁内只做内存/文件 stat，BitmapFactory 解码在锁外，
        //    避免滚动列表时把所有并发加载串行化。
        val dir = File(context.cacheDir, "previews")
        val file = File(dir, "$key.jpg")
        synchronized(diskLock) { memory.get(key)?.let { return it } }
        readCached(file, key)?.let { return it }
        // Detail previews can supply list thumbnails without decoding the original again.
        for (dimension in resolutions.filter { it > maxDim }) {
            val largerKey = cacheKey("$uriString:$version:v5", dimension)
            val larger = memory.get(largerKey) ?: readCached(File(dir, "$largerKey.jpg"), largerKey, sampleTo = maxDim)
            larger?.let {
                val edge = maxOf(it.width, it.height)
                val bitmap = if (edge <= maxDim) it else Bitmap.createScaledBitmap(it,
                    (it.width.toLong() * maxDim / edge).toInt().coerceAtLeast(1),
                    (it.height.toLong() * maxDim / edge).toInt().coerceAtLeast(1), true)
                synchronized(diskLock) { memory.put(key, bitmap) }
                return bitmap
            }
        }

        // Native decode shares the encoder's lane; cached previews never wait for it.
        return requestLocks[(uriString.hashCode() and Int.MAX_VALUE) % requestLocks.size].withLock {
            memory.get(key)?.let { return@withLock it }
            val bytes = ImageWork.run("preview $maxDim") { decode(context, uriString, maxDim) } ?: return@withLock null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withLock null
            synchronized(diskLock) {
                dir.mkdirs()
                runCatching { file.writeBytes(bytes) }
                trim(dir)
                memory.put(key, bitmap)
            }
            bitmap
        }
    }

    // Decoded outside diskLock; corrupt entries are removed and decoded again on demand.
    // sampleTo > 0 时按目标尺寸带 inSampleSize 解码，且不按原 key 入内存缓存
    // （降采样结果不应占大图档位的缓存位）。
    private fun readCached(file: File, key: String, sampleTo: Int = 0): Bitmap? {
        if (!file.isFile || file.length() == 0L) return null
        val bitmap = if (sampleTo > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val edge = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (edge / (sample * 2) >= sampleTo) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        } else {
            BitmapFactory.decodeFile(file.absolutePath)
        }
        if (bitmap == null) file.delete() else if (sampleTo <= 0) {
            file.setLastModified(System.currentTimeMillis())
            memory.put(key, bitmap)
        }
        return bitmap
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
        // Measure after entering ImageWork: a request may have waited behind an encode.
        val info = ActivityManager.MemoryInfo().also {
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
        }
        val budget = ConversionBudget.previewBytes(info.availMem, info.totalMem, info.threshold, info.lowMemory)
        if (budget == 0L) return@runCatching null
        if (uriString.startsWith("content://")) {
            context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")
                ?.use { UltraHdrBridge.nativeDecodePreview(it.fd, maxDim, budget) }
        } else {
            ParcelFileDescriptor.open(File(uriString), ParcelFileDescriptor.MODE_READ_ONLY)
                .use { UltraHdrBridge.nativeDecodePreview(it.fd, maxDim, budget) }
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

    fun clear(context: Context) {
        synchronized(diskLock) {
            memory.evictAll()
            versionCache.evictAll()
            val dir = File(context.cacheDir, "previews")
            dir.deleteRecursively()
        }
    }

    /** Drops in-memory entries under system memory pressure; disk cache is kept for a later hit. */
    fun trimMemory() {
        memory.evictAll()
        versionCache.evictAll()
    }
}

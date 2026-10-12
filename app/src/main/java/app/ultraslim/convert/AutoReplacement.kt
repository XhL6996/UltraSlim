package app.ultraslim.convert

import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import app.ultraslim.UltraSlimApp
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.data.HistoryEntity
import app.ultraslim.data.ReplacementEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** A durable, opt-in journal. Originals are only ever sent to the system's recoverable trash. */
internal object AutoReplacement {
    fun fingerprint(app: UltraSlimApp, uri: Uri): String =
        app.contentResolver.openInputStream(uri)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) { digest.update(buffer, 0, n); total += n }
            }
            check(total > 0) { "文件为空" }
            digest.digest().joinToString("") { "%02x".format(it) }
        } ?: error("无法读取文件")

    private fun requireReadable(app: UltraSlimApp, uri: Uri, history: HistoryEntity) {
        if (Build.VERSION.SDK_INT < 30) error("系统不支持回收站")
        check(!history.hadGainMap || Build.VERSION.SDK_INT >= 34) { "系统无法验证 HDR 增益图" }
        val probe = app.contentResolver.openFileDescriptor(uri, "r")?.use { UltraHdrBridge.nativeProbe(it.fd) }
        check(probe != null && probe.isSuccess && probe.width == history.width && probe.height == history.height &&
            probe.hasGainMap == history.hadGainMap && (!history.hasExif || probe.hasExif)) { "成品元数据校验失败" }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
            // ImageDecoder reports oriented dimensions; EXIF can swap width and height.
            check((info.size.width == history.width && info.size.height == history.height) ||
                (info.size.width == history.height && info.size.height == history.width)) { "系统解码尺寸不一致" }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(maxOf(1, (maxOf(info.size.width, info.size.height) + 1599) / 1600))
            decoder.setOnPartialImageListener { false }
        }
        try {
            check(!history.hadGainMap || (Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap())) { "系统无法读取成品增益图" }
        } finally { bitmap.recycle() }
    }

    private fun videoHash(app: UltraSlimApp, uri: Uri, info: MotionPhotoInfo): String {
        val fd = app.contentResolver.openFileDescriptor(uri, "r") ?: error("无法读取实况视频")
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            input.channel.position(info.videoOffset)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var remaining = info.videoLength
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                check(n > 0) { "实况视频不完整" }
                digest.update(buffer, 0, n); remaining -= n
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /** Called before saving deletes the private output. Failure only prevents replacing the original. */
    suspend fun stage(app: UltraSlimApp, history: HistoryEntity, originalHash: String?) = withContext(Dispatchers.IO) {
        try {
            check(app.settingsRepository.defaults.first().autoReplaceOriginals) { "自动替换已关闭" }
            check(history.outputBytes in 1 until history.inputBytes) { "成品未比原图更小" }
            check(originalHash != null) { "未能记录转换前的原图校验值" }
            val source = Uri.parse(history.inputUri)
            val target = MediaStoreTrashHelper.resolveToMediaStoreImageUri(app, source) ?: error("无法安全定位相册原图，请在历史中手动处理")
            check(fingerprint(app, source) == originalHash && fingerprint(app, target) == originalHash) { "原图已发生变化" }
            val output = Uri.fromFile(File(history.outputUri))
            requireReadable(app, output, history)
            val motion = MotionPhotoHelper.detect(app, source)
            if (motion != null) {
                val savedMotion = MotionPhotoHelper.detect(app, output) ?: error("成品未保留实况视频")
                check(savedMotion == motion.copy(videoOffset = savedMotion.videoOffset) &&
                    videoHash(app, source, motion) == videoHash(app, output, savedMotion)) { "实况视频校验失败" }
            }
            app.database.replacementDao().insert(ReplacementEntity(history.id, history.batchId, target.toString(), "",
                originalHash, fingerprint(app, output)))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            note(app, history.id, "原图保留：${e.message ?: "自动替换校验未通过"}")
        }
    }

    suspend fun saved(app: UltraSlimApp, history: HistoryEntity) = withContext(Dispatchers.IO) {
        val item = app.database.replacementDao().byId(history.id) ?: return@withContext
        if (item.state != "PREPARED") return@withContext
        if (history.saveState != "SAVED" || !history.outputUri.startsWith("content://")) return@withContext
        val ready = item.copy(outputUri = history.outputUri, state = "READY")
        try {
            validate(app, ready)
            app.database.replacementDao().update(ready)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            finish(app, listOf(item), "SKIPPED", "原图保留：${e.message ?: "保存校验失败"}")
        }
    }

    /** Rechecked immediately before requesting trash: files/permissions/settings can change while queued. */
    suspend fun validate(app: UltraSlimApp, item: ReplacementEntity) = withContext(Dispatchers.IO) {
        check(app.settingsRepository.defaults.first().autoReplaceOriginals) { "自动替换已关闭" }
        check(app.database.taskDao().batch(item.batchId)?.cancelled == false) { "批次已取消" }
        val history = app.database.historyDao().byId(item.historyId) ?: error("记录已移除")
        check(history.saveState == "SAVED" && history.outputUri == item.outputUri) { "成品尚未保存" }
        check(item.sourceUri != item.outputUri) { "原图与成品位置相同" }
        check(fingerprint(app, Uri.parse(item.sourceUri)) == item.sourceHash) { "原图已变化或不可访问" }
        check(fingerprint(app, Uri.parse(item.outputUri)) == item.outputHash) { "成品已变化或不可访问" }
        requireReadable(app, Uri.parse(item.outputUri), history)
    }

    suspend fun finish(app: UltraSlimApp, items: List<ReplacementEntity>, state: String, message: String) {
        app.database.replacementDao().finish(items.map { it.historyId }, state, message)
        items.forEach { note(app, it.historyId, message) }
    }

    private suspend fun note(app: UltraSlimApp, id: Long, message: String) {
        val dao = app.database.historyDao()
        dao.byId(id)?.let { dao.update(it.copy(settingsSummary = it.settingsSummary.substringBefore(" · 替换结果：") + " · 替换结果：$message")) }
    }
}

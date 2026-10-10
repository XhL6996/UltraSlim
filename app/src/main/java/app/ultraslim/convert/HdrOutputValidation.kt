package app.ultraslim.convert

import android.graphics.ImageDecoder
import android.os.Build
import java.io.File
import java.io.IOException

/** Catch platform HDR decode failures before publishing a compatible HEIC/AVIF. */
internal object HdrOutputValidation {
    fun requireReadableGainmap(file: File) {
        if (Build.VERSION.SDK_INT < 34) return // Gainmap API is unavailable.
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val edge = maxOf(info.size.width, info.size.height)
                decoder.setTargetSampleSize(maxOf(1, (edge + 1599) / 1600))
            }
        } catch (e: IOException) {
            throw IllegalStateException("当前系统无法解码导出的 HDR 图片，文件未保存。可改用 JPEG 保留原始增益图。", e)
        }
        try {
            check(bitmap.hasGainmap()) {
                "当前系统无法读取导出的 HDR 增益图，文件未保存。可改用 JPEG 保留原始增益图。"
            }
        } finally {
            bitmap.recycle()
        }
    }
}

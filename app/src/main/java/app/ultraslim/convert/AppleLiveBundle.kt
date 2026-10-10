package app.ultraslim.convert

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Transport package for the Mac importer. It is not itself an Apple Live Photo. */
internal object AppleLiveBundle {
    fun write(channel: FileChannel, info: MotionPhotoInfo, output: OutputStream, checkCancelled: () -> Unit = {}) {
        require(MotionPhotoContainer.validVideo(channel, info.videoOffset, info.videoLength)) { "动态视频不完整" }
        require(info.videoOffset + info.videoLength == channel.size()) { "动态视频位置不正确" }
        // Only HEIC output is supported. Locate the final mpvd structurally, including extended headers.
        var pos = 0L
        var stillLength = -1L
        while (pos < channel.size()) {
            val header = MotionPhotoContainer.read(channel, pos, 8)
            val type = String(header, 4, 4, Charsets.US_ASCII)
            var size = ByteBuffer.wrap(header).int.toLong() and 0xffffffffL
            var headerSize = 8
            if (size == 1L) { size = ByteBuffer.wrap(MotionPhotoContainer.read(channel, pos + 8, 8)).long; headerSize = 16 }
            if (size == 0L) size = channel.size() - pos
            require(size >= headerSize && size <= channel.size() - pos) { "HEIC 容器不完整" }
            if (pos == 0L) require(type == "ftyp") { "请先转换为 HEIC" }
            if (type == "mpvd" && pos + headerSize == info.videoOffset && pos + size == channel.size()) { stillLength = pos; break }
            pos += size
        }
        require(stillLength > 0) { "未找到 HEIC 动态视频，请重新转换原图" }
        ZipOutputStream(output).use { zip ->
            zip.setLevel(0) // media is already compressed
            fun text(name: String, value: String) { zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
            fun media(name: String, offset: Long, length: Long) {
                zip.putNextEntry(ZipEntry(name))
                val buffer = ByteBuffer.allocate(128 * 1024)
                var copied = 0L
                while (copied < length) {
                    checkCancelled(); buffer.clear(); buffer.limit(minOf(buffer.capacity().toLong(), length - copied).toInt())
                    val n = channel.read(buffer, offset + copied)
                    check(n > 0) { "文件读取不完整" }; zip.write(buffer.array(), 0, n); copied += n
                }
                zip.closeEntry()
            }
            text("manifest.json", """{"version":1,"stillFile":"photo.heic","videoFile":"video.mp4","presentationTimestampUs":${info.presentationTimestampUs}}""")
            media("photo.heic", 0, stillLength)
            media("video.mp4", info.videoOffset, info.videoLength)
            text("README.txt", "UltraSlim Apple Live Photo 导入包\n请在 Mac 上用 UltraSlim Live 打开此 ZIP，检查预览后点击导入「照片」。\n开启 iCloud 照片后可同步至 iPhone。直接把 ZIP 或 photo.heic 存入 iPhone 不会成为 Live Photo。\n")
        }
    }
}

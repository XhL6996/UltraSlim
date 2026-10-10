package app.ultraslim

import app.ultraslim.convert.AppleLiveBundle
import app.ultraslim.convert.MotionPhotoInfo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream

class AppleLiveBundleTest {
    @get:Rule val temp = TemporaryFolder()
    private fun box(type: String, data: ByteArray) = ByteBuffer.allocate(data.size + 8).putInt(data.size + 8).put(type.toByteArray()).put(data).array()
    @Test fun extractsExactStillAndVideoAndKeepsTimestamp() {
        val still = box("ftyp", "heic0000".toByteArray()) + box("mdat", ByteArray(1024) { it.toByte() })
        val movie = box("ftyp", "isom0000".toByteArray()) + box("moov", byteArrayOf()) + box("mdat", ByteArray(100000) { (it % 127).toByte() })
        for (extended in listOf(false, true)) {
            val header = if (extended) ByteBuffer.allocate(16).putInt(1).put("mpvd".toByteArray()).putLong(movie.size + 16L).array() else ByteBuffer.allocate(8).putInt(movie.size + 8).put("mpvd".toByteArray()).array()
            val file = temp.newFile().apply { writeBytes(still + header + movie) }
            val buffer = ByteArrayOutputStream()
            file.inputStream().use { AppleLiveBundle.write(it.channel, MotionPhotoInfo(123456, movie.size.toLong(), (still.size + header.size).toLong()), buffer) }
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(buffer.toByteArray().inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() } }
            assertArrayEquals(still, entries["photo.heic"])
            assertArrayEquals(movie, entries["video.mp4"])
            assertTrue(entries.getValue("manifest.json").decodeToString().contains("\"presentationTimestampUs\":123456"))
            assertEquals(setOf("manifest.json", "photo.heic", "video.mp4", "README.txt"), entries.keys)
            assertArrayEquals(still + header + movie, file.readBytes())
        }
    }
    @Test fun realHeicBundleForMacAcceptance() {
        val fixturePath = System.getenv("ULTRASLIM_LIVE_FIXTURE_DIR")
        org.junit.Assume.assumeTrue(fixturePath != null)
        val dir = java.io.File(fixturePath!!)
        val still = java.io.File(dir, "source.heic").readBytes()
        val movie = java.io.File(dir, "clip.mp4").readBytes()
        val file = temp.newFile().apply { writeBytes(still + box("mpvd", movie)) }
        val info = MotionPhotoInfo(1000000, movie.size.toLong(), still.size + 8L)
        val xmp = app.ultraslim.convert.MotionPhotoContainer.xmp(info, "image/heic").decodeToString()
        file.inputStream().use { input ->
            val detected = app.ultraslim.convert.MotionPhotoContainer.detect(input.channel, xmp)!!
            java.io.File(dir, "kotlin-export.zip").outputStream().use { AppleLiveBundle.write(input.channel, detected, it) }
        }
    }
    @Test fun rejectsMissingVideoWithoutWritingPackage() {
        val file = temp.newFile().apply { writeBytes(box("ftyp", "heic0000".toByteArray())) }
        val buffer = ByteArrayOutputStream()
        try { file.inputStream().use { AppleLiveBundle.write(it.channel, MotionPhotoInfo(-1, 32, 16), buffer) }; fail("must refuse incomplete media") } catch (_: IllegalArgumentException) { }
        assertEquals(0, buffer.size())
    }
}

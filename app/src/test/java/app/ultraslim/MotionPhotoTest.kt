package app.ultraslim

import app.ultraslim.convert.MotionPhotoContainer
import app.ultraslim.convert.MotionPhotoInfo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MotionPhotoTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun box(type: String, payload: ByteArray) = ByteBuffer.allocate(payload.size + 8)
        .putInt(payload.size + 8).put(type.toByteArray()).put(payload).array()
    private fun video(length: Int) = box("ftyp", "isom0000".toByteArray()) + box("moov", ByteArray(0)) + box("mdat", ByteArray(length - 32))
    private fun jpeg() = byteArrayOf(-1, -40, -1, -38, 0, 2, 7, 8, 9, -1, -39)
    private fun detect(bytes: ByteArray, xmp: String? = null): MotionPhotoInfo? {
        val file = temporary.newFile().apply { writeBytes(bytes) }
        return file.inputStream().use { MotionPhotoContainer.detect(it.channel, xmp) }
    }

    @Test fun jpegRoundTripUsesProductionParserAndPreservesTimestamp() {
        val info = MotionPhotoInfo(1234567, 1048576, 0)
        val still = MotionPhotoContainer.jpegWithXmp(jpeg(), info)
        assertEquals(info.copy(videoOffset = still.size.toLong()), detect(still + video(info.videoLength.toInt())))
        assertTrue(still.takeLast(9).toByteArray().contentEquals(jpeg().takeLast(9).toByteArray()))
    }

    @Test fun largeMpvdAndExtendedBoxHeadersAreDetected() {
        val movie = video(1048576)
        val ftyp = box("ftyp", "heic0000".toByteArray())
        val skipped = ByteBuffer.allocate(16).putInt(1).put("free".toByteArray()).putLong(16).array()
        val still = ftyp + skipped
        val xmp = MotionPhotoContainer.xmp(MotionPhotoInfo(987654, movie.size.toLong(), 0), "image/heic").toString(Charsets.UTF_8)
        val expected = MotionPhotoInfo(987654, movie.size.toLong(), still.size + 8L)
        assertEquals(expected, detect(still + box("mpvd", movie), xmp))
        assertEquals(expected.copy(presentationTimestampUs = -1), detect(still + box("mpvd", movie)))
        val extended = ByteBuffer.allocate(16).putInt(1).put("mpvd".toByteArray()).putLong(movie.size + 16L).array()
        assertEquals(expected.copy(presentationTimestampUs = -1, videoOffset = still.size + 16L), detect(still + extended + movie))
    }

    @Test fun scanHandlesSplitHeadersAndLeadingPartialChunk() {
        for (offset in listOf(10000, 65532)) {
            val info = MotionPhotoInfo(-1, 1, 0) // intentionally stale XMP length exercises recovery
            val head = MotionPhotoContainer.jpegWithXmp(jpeg(), info)
            val bytes = head + ByteArray(offset - head.size) + video(131072 - offset)
            assertEquals(offset.toLong(), detect(bytes)?.videoOffset)
        }
    }

    @Test fun namespaceAliasesSingleQuotesAndUnknownTimestampWork() {
        val movie = video(1024)
        val xmp = """<x:xmpmeta xmlns:x='adobe:ns:meta/' xmlns:r='http://www.w3.org/1999/02/22-rdf-syntax-ns#' xmlns:c='http://ns.google.com/photos/1.0/camera/'><r:RDF><r:Description c:MotionPhoto='1' c:MicroVideoOffset='1024' c:MotionPhotoPresentationTimestampUs='-1'/></r:RDF></x:xmpmeta>"""
        val still = box("ftyp", "heic0000".toByteArray())
        assertEquals(MotionPhotoInfo(-1, 1024, still.size + 8L), detect(still + box("mpvd", movie), xmp))
    }

    @Test fun heicXmpContainsDirectoryAndPrimaryPadding() {
        val packet = MotionPhotoContainer.xmp(MotionPhotoInfo(77, 4096, 0), "image/heic").toString(Charsets.UTF_8)
        assertTrue(packet.contains("Item:Padding=\"8\""))
        assertTrue(packet.contains("Item:Mime=\"image/heic\""))
        assertTrue(packet.contains("Camera:MotionPhotoPresentationTimestampUs=\"77\""))
    }

    @Test fun truncatedOrFtypOnlyVideosAreRejected() {
        val still = box("ftyp", "heic0000".toByteArray())
        assertNull(detect(still + box("mpvd", video(1024)).dropLast(1)))
        assertNull(detect(still + box("mpvd", box("ftyp", "isom0000".toByteArray()))))
    }

    @Test fun exactCopyRejectsTruncation() {
        val movie = video(1024)
        val file = temporary.newFile().apply { writeBytes(ByteArray(100) + movie) }
        val output = ByteArrayOutputStream()
        file.inputStream().use { MotionPhotoContainer.copyVideo(it.channel, MotionPhotoInfo(-1, 1024, 100), output) }
        assertArrayEquals(movie, output.toByteArray())
        file.writeBytes(ByteArray(100) + movie.dropLast(1))
        file.inputStream().use {
            assertThrows(IllegalArgumentException::class.java) {
                MotionPhotoContainer.copyVideo(it.channel, MotionPhotoInfo(-1, 1024, 100), ByteArrayOutputStream())
            }
        }
    }

    @Test fun jpegMpfIsRebasedAndExistingHdrMetadataSurvives() {
        val existing = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"/></rdf:RDF></x:xmpmeta>"""
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + existing.toByteArray()
        val app1 = ByteBuffer.allocate(payload.size + 4).putShort(0xffe1.toShort()).putShort((payload.size + 2).toShort()).put(payload).array()
        val tiff = ByteBuffer.allocate(58).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x4949).putShort(42).putInt(8).putShort(1)
            .putShort(0xb002.toShort()).putShort(7).putInt(32).putInt(26).putInt(0)
        val primarySize = 2 + app1.size + 8 + 58 + 9
        val oldTiffOffset = 2 + app1.size + 8
        tiff.putInt(0).putInt(primarySize).putInt(0).putInt(0)
        tiff.putInt(0).putInt(11).putInt(primarySize - oldTiffOffset).putInt(0)
        val app2 = ByteBuffer.allocate(66).putShort(0xffe2.toShort()).putShort(64).put("MPF\u0000".toByteArray()).put(tiff.array()).array()
        val original = byteArrayOf(-1, -40) + app1 + app2 + jpeg().drop(2) + jpeg()
        val result = MotionPhotoContainer.jpegWithXmp(original, MotionPhotoInfo(4, 1024, 0))
        val text = String(result, Charsets.ISO_8859_1)
        assertTrue(text.contains("hdrgm:Version=\"1.0\""))
        assertTrue(text.contains("Item:Semantic=\"GainMap\""))
        val newTiff = text.indexOf("MPF\u0000") + 4
        val buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        val newPrimarySize = primarySize + result.size - original.size
        assertEquals(newPrimarySize, buffer.getInt(newTiff + 30))
        assertEquals(newPrimarySize, newTiff + buffer.getInt(newTiff + 50))
        assertArrayEquals(jpeg(), result.copyOfRange(newPrimarySize, result.size))
    }
}

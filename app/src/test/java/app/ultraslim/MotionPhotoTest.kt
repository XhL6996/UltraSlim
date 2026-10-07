package app.ultraslim

import app.ultraslim.convert.MotionPhotoHelper
import app.ultraslim.convert.MotionPhotoInfo
import app.ultraslim.model.OutputFormat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MotionPhotoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testMotionPhotoDetectionAndVerification() {
        // Construct a mock JPEG Motion Photo with standard XMP and trailing MP4 ftyp box
        val file = tempFolder.newFile("mock_motion.jpg")
        val videoLen = 1024L
        val timestampUs = 1234567L

        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description
              xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
              xmlns:Container="http://ns.google.com/photos/1.0/container/"
              xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
              GCamera:MotionPhoto="1"
              GCamera:MotionPhotoPresentationTimestampUs="$timestampUs">
              <Container:Directory>
                <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"/>
                <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLen"/>
              </Container:Directory>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>""".toByteArray(Charsets.UTF_8)

        // Mock image part (32KB padding + SOI + APP1 XMP)
        val imagePart = ByteArray(32768) { 0xFF.toByte() }
        imagePart[0] = 0xFF.toByte()
        imagePart[1] = 0xD8.toByte()
        System.arraycopy(xmp, 0, imagePart, 100, xmp.size)

        // Mock MP4 video part (starts with ftyp box)
        val videoPart = ByteArray(videoLen.toInt()) { 0x55.toByte() }
        val ftypHeader = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(24) // box size
            .put(0x66.toByte()).put(0x74.toByte()).put(0x79.toByte()).put(0x70.toByte()) // "ftyp"
            .array()
        System.arraycopy(ftypHeader, 0, videoPart, 0, 8)

        file.writeBytes(imagePart + videoPart)

        // Verify detection logic
        val detected = detectDirect(file)
        assertNotNull(detected)
        assertEquals(timestampUs, detected?.presentationTimestampUs)
        assertEquals(videoLen, detected?.videoLength)
    }

    private fun detectDirect(file: File): MotionPhotoInfo? {
        val fileSize = file.length()
        val headerBytes = ByteArray(minOf(fileSize, 256 * 1024L).toInt())
        file.inputStream().use { it.read(headerBytes) }
        val headerStr = String(headerBytes, Charsets.UTF_8)

        if (!headerStr.contains("MotionPhoto=\"1\"")) return null

        val tsMatch = Regex("""PresentationTimestampUs="?([0-9]+)"?""").find(headerStr)
        val timestampUs = tsMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val lenMatch = Regex("""Item:Length="?([0-9]+)"?""").find(headerStr)
        val videoLen = lenMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        val ftypOffset = fileSize - videoLen
        val boxBuf = ByteArray(8)
        file.inputStream().use {
            it.skip(ftypOffset)
            it.read(boxBuf)
        }
        val isFtyp = boxBuf[4] == 0x66.toByte() && boxBuf[5] == 0x74.toByte() &&
                boxBuf[6] == 0x79.toByte() && boxBuf[7] == 0x70.toByte()

        return if (isFtyp) MotionPhotoInfo(timestampUs, videoLen, ftypOffset) else null
    }
}

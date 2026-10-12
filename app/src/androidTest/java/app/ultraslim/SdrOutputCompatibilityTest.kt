package app.ultraslim

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.convert.SdrOutputValidation
import app.ultraslim.model.OutputFormat
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SdrOutputCompatibilityTest {
    @Test fun oddSdrAvifGridsDecodeAtOriginalDimensionsWithPlatformDecoder() {
        assumeTrue(Build.VERSION.SDK_INT >= 31 && ImageDecoder.isMimeTypeSupported("image/avif"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "sdr-platform-regression").apply { mkdirs() }
        try {
            for ((width, height) in listOf(513 to 385, 5001 to 513, 513 to 5001, 5002 to 513, 5002 to 514)) {
                val input = File(dir, "source-$width-$height.jpg")
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(0xff3982ba.toInt())
                    input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                } finally { bitmap.recycle() }
                val output = File(dir, "output-$width-$height.avif")
                ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { a ->
                    ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { b ->
                        val result = UltraHdrBridge.nativeTranscode(a.fd, b.fd, 2, 90, 60, 4, true, true, true, 2, 100, 2, true)
                        assertTrue(result.error, result.isSuccess)
                        assertFalse(result.hasGainMap)
                        assertEquals(width, result.width)
                        assertEquals(height, result.height)
                    }
                }
                // Check the packaged native dependency too, not just the host libheif.
                java.io.DataInputStream(output.inputStream()).use { stream ->
                    val boxSize = stream.readInt()
                    assertEquals(0x66747970, stream.readInt()) // ftyp
                    assertTrue(boxSize in 16..256 && boxSize % 4 == 0)
                    stream.readLong() // major brand + minor version
                    val brands = List((boxSize - 16) / 4) { stream.readInt() }
                    assertFalse("SDR must not claim tmap content", 0x746d6170 in brands)
                }
                // Same pre-publication check as the worker, plus full-size independent decoding.
                SdrOutputValidation.requireReadable(output, OutputFormat.AVIF)
                val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(output)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                try {
                    assertEquals(width, decoded.width)
                    assertEquals(height, decoded.height)
                    if (Build.VERSION.SDK_INT >= 34) assertFalse(decoded.hasGainmap())
                } finally { decoded.recycle() }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun publicationCheckRejectsBrokenSdrOutput() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "broken-sdr-output.jpg")
        try {
            file.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0))
            val error = assertThrows(IllegalStateException::class.java) {
                SdrOutputValidation.requireReadable(file, OutputFormat.JPEG)
            }
            assertTrue(error.message.orEmpty().contains("SDR JPEG"))
            assertTrue(error.message.orEmpty().contains("文件未保存"))
        } finally { file.delete() }
    }
}

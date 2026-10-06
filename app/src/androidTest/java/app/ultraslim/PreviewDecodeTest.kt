package app.ultraslim

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.bridge.UltraHdrBridge
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewDecodeTest {

    /**
     * Decodes a big gain map AVIF pushed to the device through the exact preview
     * path the app uses, and dumps the JPEG next to it for inspection.
     */
    @Test fun decodeBigAvifPreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = File(
            instrumentation.targetContext.getExternalFilesDir(null),
            "preview_probe.avif",
        )
        assertTrue("push preview_probe.avif first", source.exists())

        // Stage 1: raw Y plane straight out of heif_decode_image (no conversion).
        val packed = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            UltraHdrBridge.nativeDebugPlaneY(pfd.fd)
        }
        assertNotNull("nativeDebugPlaneY returned null", packed)
        val bb = java.nio.ByteBuffer.wrap(packed).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val w = bb.int
        val h = bb.int
        val yPlane = ByteArray(packed!!.size - 8)
        System.arraycopy(packed, 8, yPlane, 0, yPlane.size)
        File(instrumentation.targetContext.cacheDir, "yplane.bin").writeBytes(yPlane)
        val staleY = staleRowRatio(yPlane, w, h)
        android.util.Log.i("UltraSlimVerify", "Y plane ${w}x$h staleRatio=$staleY")

        // Stage 2: the converted preview jpeg.
        val jpeg = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            UltraHdrBridge.nativeDecodePreview(pfd.fd, 1600)
        }
        assertNotNull("nativeDecodePreview returned null", jpeg)
        val out = File(instrumentation.targetContext.cacheDir, "preview_probe.jpg")
        out.writeBytes(jpeg!!)
        val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val staleJ = staleRowRatioBitmap(bmp)
        android.util.Log.i("UltraSlimVerify", "preview jpeg staleRatio=$staleJ")
        assertTrue(
            "Y smeared=$staleY jpeg smeared=$staleJ",
            staleY < 0.5 && staleJ < 0.5,
        )
    }

    private fun staleRowRatio(plane: ByteArray, w: Int, h: Int): Double {
        var stale = 0
        for (y in 1 until h) {
            var sum = 0L
            var prevSum = 0L
            for (x in w / 2 until w) {
                sum += plane[y * w + x].toInt() and 0xFF
                prevSum += plane[(y - 1) * w + x].toInt() and 0xFF
            }
            val n = (w - w / 2)
            if (kotlin.math.abs(sum.toDouble() / n - prevSum.toDouble() / n) < 0.35) stale++
        }
        return stale.toDouble() / h
    }

    private fun staleRowRatioBitmap(bmp: android.graphics.Bitmap): Double {
        var stale = 0
        val px = IntArray(bmp.width)
        var prevMean = DoubleArray(3)
        for (y in 0 until bmp.height) {
            bmp.getPixels(px, 0, bmp.width, 0, y, bmp.width, 1)
            var r = 0.0; var g = 0.0; var b = 0.0
            for (x in bmp.width / 2 until bmp.width) {
                val p = px[x]
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            val n = (bmp.width - bmp.width / 2).toDouble()
            val mean = doubleArrayOf(r / n, g / n, b / n)
            if (y > 0) {
                val d = kotlin.math.abs(mean[0] - prevMean[0]) +
                    kotlin.math.abs(mean[1] - prevMean[1]) +
                    kotlin.math.abs(mean[2] - prevMean[2])
                if (d < 0.35) stale++
            }
            prevMean = mean
        }
        return stale.toDouble() / bmp.height
    }
}

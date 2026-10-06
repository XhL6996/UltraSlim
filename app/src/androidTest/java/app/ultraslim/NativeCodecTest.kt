package app.ultraslim

import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import app.ultraslim.bridge.UltraHdrBridge
import java.io.File
import org.junit.Test
import org.junit.Assert.*

class NativeCodecTest {
    @Test fun alignedGraphicsJniLoads() {
        // JNI_OnLoad registers the unchanged 1.0.1 API on the rebuilt native component.
        System.loadLibrary("androidx.graphics.path")
    }

    @Test fun hdrRoundtripAllFormatsThroughJni() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.cacheDir,"native-regression").apply { mkdirs() }
        try {
            val source = File(dir,"source.jpg")
            instrumentation.context.assets.open("apple_gainmap_new.jpg").use { input -> source.outputStream().use { input.copyTo(it) } }
            for (codec in 0..2) {
                var input = source
                repeat(2) { pass ->
                    val output = File(dir,"out-$codec-$pass")
                    ParcelFileDescriptor.open(input,ParcelFileDescriptor.MODE_READ_ONLY).use { a ->
                        ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { b ->
                            val result = UltraHdrBridge.nativeTranscode(a.fd,b.fd,codec,85,60,4,true,true,true,2,50,2,false)
                            assertTrue("codec=$codec pass=$pass ${result.error}",result.isSuccess)
                            val probe = UltraHdrBridge.nativeProbe(b.fd)
                            assertTrue(probe.error,probe.isSuccess); assertTrue(probe.hasGainMap); assertTrue(probe.hasExif)
                            assertEquals(result.gainmapWidth,probe.gainmapWidth)
                            assertNotNull(UltraHdrBridge.nativeDecodePreview(b.fd,128))
                        }
                    }
                    input = output
                }
            }
        } finally { dir.deleteRecursively() }
    }
    /** Opt-in regression against only the photos the user has already queued. No history/gallery writes. */
    @Test fun selectedPhotoPreservation() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("selectedPhotos") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UltraSlimApp
        app.conversionRepository.awaitReady()
        val dir = File(app.cacheDir, "selected-preservation").apply { mkdirs() }
        val items = app.conversionRepository.queue.value.filter { it.probe?.hasGainMap == true }.take(3)
        assertTrue("No selected HDR photos", items.isNotEmpty())
        for ((index, item) in items.withIndex()) {
            val source = File(dir, "source-$index.${if(item.probe!!.container == 0) "jpg" else "heic"}")
            app.contentResolver.openInputStream(android.net.Uri.parse(item.uri))!!.use { input -> source.outputStream().use { input.copyTo(it) } }
            val output = File(dir, "output-$index.avif")
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { a ->
                ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { b ->
                    val result = UltraHdrBridge.nativeTranscode(a.fd, b.fd, 2, 82, 1, 128, false, true, true, 1, 100, 2, true)
                    assertTrue(result.error, result.isSuccess)
                    val input = UltraHdrBridge.nativeProbe(a.fd)
                    val probe = UltraHdrBridge.nativeProbe(b.fd)
                    assertTrue(probe.isSuccess && probe.hasGainMap)
                    assertEquals(input.gainmapWidth.toLong() * input.gainmapHeight, probe.gainmapWidth.toLong() * probe.gainmapHeight)
                    android.util.Log.i("UltraSlimVerify", "sample=$index input=${result.inputBytes} output=${result.outputBytes} map=${probe.gainmapWidth}x${probe.gainmapHeight} ms=${result.durationMs}")
                }
            }
        }
    }

}

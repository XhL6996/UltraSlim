package app.ultraslim

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.convert.AutoReplacement
import app.ultraslim.convert.MediaStoreSaver
import app.ultraslim.convert.MediaStoreTrashHelper
import app.ultraslim.convert.SourceMedia
import app.ultraslim.convert.ImageWork
import app.ultraslim.convert.ConversionBudget
import app.ultraslim.convert.MotionPhotoHelper
import app.ultraslim.convert.HdrOutputValidation
import app.ultraslim.convert.SdrOutputValidation
import app.ultraslim.model.OutputFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Checks the real Camera directory without changing preferences, tasks or existing photos. */
class CameraAlbumPublicationTest {
    private fun report(message: String) {
        android.util.Log.i("UltraSlimCameraCheck", message)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "\n$message\n")
        })
    }

    /** Optional on-device diagnosis; no queue, settings, history or original mutation. */
    @Test fun diagnoseCameraSampleWithoutReplacingOriginal() = runBlocking {
        val value = InstrumentationRegistry.getArguments().getString("cameraSourceUri")
        assumeTrue(value != null)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UltraSlimApp
        val source = Uri.parse(value!!)
        val output = File(app.cacheDir, "camera-diagnostic-${UUID.randomUUID()}.avif")
        val before = AutoReplacement.fingerprint(app, source)
        try {
            val probe = ImageWork.run { app.contentResolver.openFileDescriptor(source, "r")!!.use {
                UltraHdrBridge.nativeProbe(it.fd)
            } }
            assertTrue(probe.error, probe.isSuccess)
            val motion = MotionPhotoHelper.detect(app, source)
            report("source=${probe.containerLabel} ${probe.width}x${probe.height} hdr=${probe.hasGainMap} exif=${probe.hasExif} motion=${motion != null}")
            val result = ImageWork.run("camera sample diagnosis") {
                val memory = android.app.ActivityManager.MemoryInfo().also {
                    app.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
                }
                app.contentResolver.openFileDescriptor(source, "r")!!.use { input ->
                    check(ConversionBudget.canStart(probe.width, probe.height, probe.gainmapWidth,
                        probe.gainmapHeight, probe.hasGainMap, input.statSize, memory.availMem,
                        memory.totalMem, memory.threshold, memory.lowMemory)) { "Insufficient conversion memory" }
                    ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { target ->
                        UltraHdrBridge.nativeTranscode(input.fd, target.fd, 2, 92, 60, 4,
                            true, true, true, 1, 100, 1, true, retrySmaller = true,
                            avifGainmapCompatibility = true)
                    }
                }
            }
            assertTrue(result.error, result.isSuccess)
            if (probe.hasGainMap) HdrOutputValidation.requireReadableGainmap(output)
            else SdrOutputValidation.requireReadable(output, OutputFormat.AVIF)
            val outputMotion = MotionPhotoHelper.detect(app, Uri.fromFile(output))
            val request = MediaStoreTrashHelper.prepareTrashRequest(app, listOf(source))
            report("outputReadable=true inputBytes=${result.inputBytes} outputBytes=${result.outputBytes} smaller=${result.outputBytes < result.inputBytes} motionPreserved=${motion == null || outputMotion != null} trashRequestCreated=${request.sender != null} trashReason=${request.message}")
            assertEquals(before, AutoReplacement.fingerprint(app, source))
            assertEquals(MediaStoreTrashHelper.OriginalState.PRESENT, MediaStoreTrashHelper.originalState(app, source))
            // Never launch the returned sender: creating it does not approve or perform trashing.
        } finally { output.delete() }
    }

    @Test fun avifCanBePublishedBesideCameraSource() {
        assumeTrue(Build.VERSION.SDK_INT >= 31 && ImageDecoder.isMimeTypeSupported("image/avif"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UltraSlimApp
        val id = "UltraSlimCameraCheck-${UUID.randomUUID()}"
        val input = File(app.cacheDir, "$id.jpg")
        val output = File(app.cacheDir, "$id.avif")
        // Optional existing source is read only. Only URIs created by this test are deleted.
        val existing = InstrumentationRegistry.getArguments().getString("cameraSourceUri")?.let(Uri::parse)
        var ownedSource: Uri? = null
        var published: Uri? = null
        try {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xff3982ba.toInt())
                input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally { bitmap.recycle() }
            val source = existing ?: MediaStoreSaver.save(app, input, "$id.jpg", OutputFormat.JPEG,
                relativePath = "DCIM/Camera/")!!.also { ownedSource = it }
            val before = AutoReplacement.fingerprint(app, source)
            val metadata = requireNotNull(SourceMedia.read(app, source))
            assertEquals("DCIM/Camera/", metadata.relativePath)
            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { a ->
                ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { b ->
                    val result = UltraHdrBridge.nativeTranscode(a.fd, b.fd, 2, 90, 60, 4,
                        true, true, true, 2, 100, 1, true)
                    assertTrue(result.error, result.isSuccess)
                }
            }
            published = MediaStoreSaver.save(app, output, "$id.avif", OutputFormat.AVIF,
                taskId = id, relativePath = metadata.relativePath, volume = metadata.volume,
                dateTaken = metadata.dateTaken, relatedUri = source)
            assertNotNull("Camera publication must not fall back to UltraSlim", published)
            val saved = requireNotNull(SourceMedia.read(app, published!!))
            assertEquals(metadata.volume, saved.volume)
            assertEquals(metadata.relativePath, saved.relativePath)
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, published!!))
            try { assertEquals(64, decoded.width); assertEquals(64, decoded.height) }
            finally { decoded.recycle() }
            assertEquals(before, AutoReplacement.fingerprint(app, source))
            assertEquals(MediaStoreTrashHelper.OriginalState.PRESENT, MediaStoreTrashHelper.originalState(app, source))
            report("AVIF publication verified: ${saved.volume}/${saved.relativePath}; original unchanged")
        } finally {
            published?.let { app.contentResolver.delete(it, null, null) }
            ownedSource?.let { app.contentResolver.delete(it, null, null) }
            input.delete(); output.delete()
        }
    }
}

package app.ultraslim

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.convert.*
import app.ultraslim.data.HistoryEntity
import app.ultraslim.model.OutputFormat
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AppleLiveIntegrationTest {
    /** Stage real JPEG and H.264/AAC test clip in an isolated QA package. Keep output for Mac validation. */
    @Test fun androidExportFeedsMacImporter() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("appleLiveSample") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "apple-live-source.jpg")
        val clip = File(context.filesDir, "apple-live-clip.mp4").readBytes()
        val dir = File(context.filesDir, "outputs/live-qa").apply { mkdirs() }
        val input = File(dir, "motion.jpg")
        val info = MotionPhotoInfo(1_000_000, clip.size.toLong(), 0)
        input.writeBytes(MotionPhotoContainer.jpegWithXmp(source.readBytes(), info) + clip)
        val motion = MotionPhotoHelper.detect(context, Uri.fromFile(input))!!
        val heic = File(dir, "motion.heic")
        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { a ->
            ParcelFileDescriptor.open(heic, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use { b ->
                val result = UltraHdrBridge.nativeTranscode(a.fd,b.fd,1,92,60,4,true,true,true,1,100,4,true,
                    MotionPhotoContainer.xmp(motion,"image/heic"),heicGainmapCompatibility=true)
                assertTrue(result.error,result.isSuccess)
            }
        }
        assertTrue(MotionPhotoHelper.attachMotionPhoto(context,Uri.fromFile(input),heic,OutputFormat.HEIC,motion))
        val row = HistoryEntity(displayName="motion.heic",inputUri=Uri.fromFile(input).toString(),outputUri=heic.path,
            inputBytes=input.length(),outputBytes=heic.length(),width=4416,height=5888,gainmapWidth=2208,gainmapHeight=2944,
            hadGainMap=true,format="HEIC",inputFormat="JPEG",durationMs=0.0,maxContentBoost=4.93f,createdAt=0,status="DONE")
        val bundle = File(dir,"AppleLive.zip").apply { createNewFile() }
        AppleLiveExporter.export(context,row,OutputStore.uri(context,bundle.path))
        ZipFile(bundle).use { zip ->
            assertArrayEquals(clip,zip.getInputStream(zip.getEntry("video.mp4")).readBytes())
            val still=File(dir,"photo.heic").apply { writeBytes(zip.getInputStream(zip.getEntry("photo.heic")).readBytes()) }
            ParcelFileDescriptor.open(still,ParcelFileDescriptor.MODE_READ_ONLY).use { val probe=UltraHdrBridge.nativeProbe(it.fd); assertTrue(probe.error,probe.isSuccess); assertTrue(probe.hasGainMap) }
            assertTrue(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText().contains("1000000"))
        }
        assertTrue(heic.isFile && source.isFile)
    }
}

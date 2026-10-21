package app.ultraslim

import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.bridge.UltraHdrBridge
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PreviewBudgetTest {
    @Test fun previewBudgetRejectsInsufficientMemoryAndDefaultRemainsCompatible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = File(instrumentation.targetContext.cacheDir, "preview-budget-${UUID.randomUUID()}.jpg")
        try {
            instrumentation.context.assets.open("apple_gainmap_new.jpg").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                assertNull(UltraHdrBridge.nativeDecodePreview(fd.fd, 128, 0))
                assertNull(UltraHdrBridge.nativeDecodePreview(fd.fd, 128, -1))
                assertNull(UltraHdrBridge.nativeDecodePreview(fd.fd, 128, 1))
                val bytes = UltraHdrBridge.nativeDecodePreview(fd.fd, 128)
                assertNotNull(bytes)
                val bitmap = BitmapFactory.decodeByteArray(bytes!!, 0, bytes.size)
                assertNotNull(bitmap)
                assertEquals(128, maxOf(bitmap.width, bitmap.height))
                bitmap.recycle()
            }
        } finally { source.delete() }
    }
}

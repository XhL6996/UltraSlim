package app.ultraslim

import app.ultraslim.model.OutputFormat
import app.ultraslim.util.*
import org.junit.Assert.*
import org.junit.Test

class FormattingTest {
    @Test fun formatsAndSignedSavings() {
        assertEquals("image/jpeg", OutputFormat.parse("JPEG").mimeType)
        assertEquals("image/heic", OutputFormat.parse("heic").mimeType)
        assertEquals("image/avif", OutputFormat.parse("AVIF").mimeType)
        assertEquals("+25%", formatSavedPercent(100,125))
        assertEquals("−25%", formatSavedPercent(100,75))
        assertEquals("+25 B", formatByteDelta(25))
        assertEquals("−25 B", formatByteDelta(-25))
    }
    @Test fun filenamesAreReadableStableAndSafe() {
        val first = outputName("IMG_20261006.jpg", "AVIF", "task-1", 0)
        assertTrue(first.matches(Regex("IMG_20261006_Slim_[0-9a-f]{8}\\.avif")))
        assertEquals(first, outputName("IMG_20261006.jpg", "avif", "task-1", 0))
        assertNotEquals(first, outputName("IMG_20261006.jpg", "avif", "task-2", 0))
        assertTrue(outputName("81_1_1_5767931.jpg", "heic", "task-3", 0).startsWith("Photo_"))
        assertFalse(outputName("../a/b.jpg", "avif", "task", 0).contains('/'))
    }

}

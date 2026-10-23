package app.ultraslim

import app.ultraslim.model.OutputFormat
import app.ultraslim.util.*
import org.junit.Assert.*
import org.junit.Test

class FormattingTest {
    @Test fun keptFilenamesRetainNumericScreenshotMotionAndLongOriginalStems() {
        for (source in listOf("a.jpg", "a.jpeg", "Screenshot_20250101.jpg", "123456.jpeg",
            "holiday.edit.v2.jpg", "照片_Slim.jpg", "a:b.jpg", " name .jpg", "a".repeat(120) + ".jpg")) {
            assertEquals(source.substringBeforeLast('.') + ".heic",
                outputName(source, "HEIC", "any-task", 0, motionPhoto = true, keepFilename = true))
        }
    }

    @Test fun keptFilenamesUseContentHashOnlyOnConflictAndExtendCollidingHashes() {
        val hash = "0123456789abcdef".repeat(4)
        assertEquals("a.heic", keptOutputName("a.heic", setOf("b.heic"), hash))
        assertEquals("a_01234567.heic", keptOutputName("a.heic", setOf("a.heic"), hash))
        assertEquals("a_0123456789ab.heic",
            keptOutputName("a.heic", setOf("A.HEIC", "a_01234567.heic"), hash))
        val names = mutableSetOf("a.heic")
        repeat(20) { assertTrue(names.add(keptOutputName("a.heic", names, hash))) }
        assertEquals(21, names.size)
        val longName = "图".repeat(80) + ".heic"
        val safe = keptOutputName(longName, setOf(longName), hash)
        assertTrue(safe.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(safe.endsWith("_01234567.heic"))
    }

    @Test fun providerNumberingAndHiddenHashCollisionsAreReplacedWithLongerHashes() {
        val hash = "0123456789abcdef".repeat(4)
        var actual = "a (1).heic"
        val attempts = mutableListOf<String>()
        confirmKeptOutputName("a.heic", "a.heic", emptySet(), hash, { actual }) {
            attempts += it
            if (it == "a_01234567.heic") error("Hidden existing file")
            actual = it
        }
        assertEquals(listOf("a_01234567.heic", "a_0123456789ab.heic"), attempts)
        assertEquals("a_0123456789ab.heic", actual)
    }

    @Test fun formatsAndSignedSavings() {
        assertEquals("image/jpeg", OutputFormat.parse("JPEG").mimeType)
        assertEquals("image/heic", OutputFormat.parse("heic").mimeType)
        assertEquals("image/avif", OutputFormat.parse("AVIF").mimeType)
        assertEquals("image/jxl", OutputFormat.parse("JXL").mimeType)
        assertEquals("image/jxl", OutputFormat.parse("jxl").mimeType)
        assertEquals("+25%", formatSavedPercent(100,125))
        assertEquals("−25%", formatSavedPercent(100,75))
        assertEquals("+25 B", formatByteDelta(25))
        assertEquals("−25 B", formatByteDelta(-25))
    }
    @Test fun filenamesAreReadableStableAndSafe() {
        val first = outputName("IMG_20261006.jpg", "AVIF", "task-1", 0)
        assertTrue(first.matches(Regex("IMG_20261006_Slim_[0-9a-f]{8}\\.avif")))
        assertEquals(first, outputName("IMG_20261006.jpg", "avif", "task-1", 0))
        assertTrue(outputName("photo.jpg", "heic", "motion", 0, motionPhoto = true).endsWith("MP.heic"))
        assertNotEquals(first, outputName("IMG_20261006.jpg", "avif", "task-2", 0))
        assertTrue(outputName("81_1_1_5767931.jpg", "heic", "task-3", 0).startsWith("Photo_"))
        assertFalse(outputName("../a/b.jpg", "avif", "task", 0).contains('/'))
    }

    @Test fun convertedScreenshotsDoNotAdvertiseANewScreenCapture() {
        for (name in listOf("Screenshot_20250101.png", "screen_shot-123.jpg", "ScreenCapture.jpg", "截屏2025.jpg", "截图.png")) {
            assertTrue(outputName(name, "avif", "same-task", 0).startsWith("Photo_"))
        }
    }

    @Test fun testGroupByTimeSessionClustersCloseTimestamps() {
        data class MockItem(val id: String, val timestamp: Long, val batchId: String = "")
        val t0 = 1700000000000L
        val items = listOf(
            MockItem("1", t0),
            MockItem("2", t0 + 1000L),
            MockItem("3", t0 + 2000L),
            MockItem("4", t0 + 10 * 60 * 1000L),
            MockItem("5", t0 + 10 * 60 * 1000L + 500L),
        )

        val groups = groupByTimeSession(
            items = items,
            timestampSelector = { it.timestamp },
            batchIdSelector = { it.batchId },
        )

        assertEquals(2, groups.size)
        // Group 1 (newest first): items 5 then 4
        assertEquals(listOf("5", "4"), groups[0].items.map { it.id })
        // Group 2 (newest first): items 3, 2, 1
        assertEquals(listOf("3", "2", "1"), groups[1].items.map { it.id })
    }

    @Test fun testGroupByTimeSessionHonorsBatchId() {
        data class MockItem(val id: String, val timestamp: Long, val batchId: String = "")
        val t0 = 1700000000000L
        val items = listOf(
            MockItem("1", t0, "batch-A"),
            MockItem("2", t0 + 500L, "batch-B"),
        )
        val groups = groupByTimeSession(
            items = items,
            timestampSelector = { it.timestamp },
            batchIdSelector = { it.batchId },
        )
        assertEquals(2, groups.size)
    }

}

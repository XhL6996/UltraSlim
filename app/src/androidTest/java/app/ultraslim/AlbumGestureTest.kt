package app.ultraslim

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File

class AlbumGestureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val photos = mutableListOf<Uri>()
    @Before fun seed() {
        val app = compose.activity.application as UltraSlimApp
        kotlinx.coroutines.runBlocking {
            app.conversionRepository.awaitReady()
            app.conversionRepository.clearAll()
            app.conversionRepository.flush()
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        repeat(14) { index ->
            val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "gesture-$index.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GestureAlbum$index")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })!!
            photos += uri
            app.contentResolver.openOutputStream(uri)!!.use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        image.recycle()
    }
    @After fun cleanup() { photos.forEach { compose.activity.contentResolver.delete(it, null, null) } }
    @Test fun scrollingAndSwitchingTabsNeverCollapsesSheetOrImportsByAccident() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("相册导入").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("相册导入").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("album-list").fetchSemanticsNodes().isNotEmpty() }
        val before = compose.onNodeWithText("导入照片").fetchSemanticsNode().boundsInRoot.top
        repeat(3) { compose.onNodeWithTag("album-list").performTouchInput { swipeUp() } }
        compose.onNodeWithText("导入照片").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("导入照片").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithText("智能探查 HDR").performClick()
        compose.onNodeWithText("按相册导入").performClick()
        assertEquals(before, compose.onNodeWithText("导入照片").fetchSemanticsNode().boundsInRoot.top, 1f)
        repeat(3) { compose.onNodeWithTag("album-list").performTouchInput { swipeDown() } }
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("album-list").assertDoesNotExist()
    }
}

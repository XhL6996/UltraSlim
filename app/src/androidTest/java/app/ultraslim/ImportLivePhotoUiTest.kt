package app.ultraslim

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.Rule
import org.junit.Test

class ImportLivePhotoUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun importFilterIsOffByDefaultAndSurvivesActivityRecreation() {
        compose.onNodeWithTag("ignore-live-photos").assertIsOff().performClick().assertIsOn()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("ignore-live-photos").assertIsOn()
        compose.onNodeWithText("导入时跳过检测到内嵌动态视频的照片").assertIsDisplayed()
        File(compose.activity.filesDir, "import-live-option.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("ignore-live-photos").performClick().assertIsOff()
    }
}

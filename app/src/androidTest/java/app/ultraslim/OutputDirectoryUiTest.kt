package app.ultraslim

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.content.IntentFilter
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.ultraslim.data.ConversionDefaults
import app.ultraslim.ui.settings.SettingsScreen
import app.ultraslim.ui.components.ConversionOptionsPanel
import app.ultraslim.ui.components.OptionsCallbacks
import app.ultraslim.ui.theme.AppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OutputDirectoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mainPanelFileOptionsStayIndependentAndRespectConversionLock() {
        var defaults by mutableStateOf(ConversionDefaults(autoSave = false))
        var enabled by mutableStateOf(true)
        compose.activity.setContent {
            AppTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ConversionOptionsPanel(defaults, OptionsCallbacks(
                        {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                        onKeepFilenameChange = { defaults = defaults.copy(keepFilename = it) },
                    ), enabled = enabled)
                }
            }
        }
        compose.onNodeWithText("保留文件名").performScrollTo().performClick()
        compose.onNodeWithText("保留修改时间").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(defaults.keepFilename)
            assertFalse(defaults.autoSave)
            enabled = false
        }
        compose.onNodeWithText("保留文件名").performScrollTo().assertIsNotEnabled()
    }

    @Test fun settingsPersistFileOptions() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UltraSlimApp
        val original = runBlocking { app.settingsRepository.defaults.first() }
        try {
            runBlocking { app.settingsRepository.setDefaults(ConversionDefaults()) }
            compose.activity.setContent { AppTheme { SettingsScreen(onBack = {}) } }
            compose.onNodeWithText("保留文件名").performScrollTo().performClick()
            compose.waitUntil(5_000) { runBlocking { app.settingsRepository.defaults.first().keepFilename } }
            compose.onNodeWithText("保留修改时间").assertDoesNotExist()
            assertTrue(runBlocking { app.settingsRepository.defaults.first().keepFilename })
        } finally { runBlocking { app.settingsRepository.setDefaults(original) } }
    }

    @Test fun mainPanelFolderSelectorFollowsAutomaticSavingAndRemembersDestination() {
        var defaults by mutableStateOf(ConversionDefaults(outputDirectoryUri = "content://test/tree/photos",
            outputDirectoryName = "My photos"))
        compose.activity.setContent {
            AppTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ConversionOptionsPanel(defaults, OptionsCallbacks(
                        {}, {}, {}, {}, {}, {}, {}, { defaults = defaults.copy(autoSave = it) }, {}, {}, {},
                        onChooseOutputDirectory = {},
                    ))
                }
            }
        }
        compose.onNodeWithText("输出文件夹").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("自动保存到文件夹").performScrollTo().performClick()
        compose.onNodeWithText("输出文件夹").assertDoesNotExist()
        compose.onNodeWithText("自动保存到文件夹").performScrollTo().performClick()
        compose.onNodeWithText("输出文件夹").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("My photos").assertExists()
        compose.runOnIdle { defaults = defaults.copy(autoReplaceOriginals = true) }
        compose.onNodeWithText("原相册 · 选择文件夹后关闭自动替换").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { defaults = defaults.copy(autoReplaceOriginals = false) }
        compose.onNodeWithText("My photos").performScrollTo().assertIsDisplayed()
    }

    @Test fun settingsHideFolderAndResetActionWhenAutomaticSavingIsOff() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as UltraSlimApp
        val original = runBlocking { app.settingsRepository.defaults.first() }
        try {
            runBlocking { app.settingsRepository.setDefaults(ConversionDefaults(
                outputDirectoryUri = "content://test/tree/photos", outputDirectoryName = "My photos")) }
            compose.activity.setContent { AppTheme { SettingsScreen(onBack = {}) } }
            compose.waitForIdle()
            compose.onNodeWithText("输出文件夹").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("自动保存到文件夹").performScrollTo().performClick()
            compose.waitUntil(5_000) { runBlocking { !app.settingsRepository.defaults.first().autoSave } }
            compose.waitForIdle()
            compose.onNodeWithText("输出文件夹").assertDoesNotExist()
            compose.onNodeWithText("恢复默认相册").assertDoesNotExist()
            compose.onNodeWithText("自动保存到文件夹").performScrollTo().performClick()
            compose.waitUntil(5_000) { runBlocking { app.settingsRepository.defaults.first().autoSave } }
            compose.waitForIdle()
            compose.onNodeWithText("输出文件夹").performScrollTo().assertIsDisplayed()
            assertEquals("My photos", runBlocking { app.settingsRepository.defaults.first().outputDirectoryName })
        } finally { runBlocking { app.settingsRepository.setDefaults(original) } }
    }

    @Test fun systemPickerSelectionCancellationAndReset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as UltraSlimApp
        val original = runBlocking { app.settingsRepository.defaults.first() }
        val tree = DocumentsContract.buildTreeDocumentUri("app.ultraslim.tests.documents", "output")
        try {
            runBlocking { app.settingsRepository.setDefaults(ConversionDefaults()) }
            compose.activity.setContent { AppTheme { SettingsScreen(onBack = {}) } }
            compose.waitForIdle()
            // Android owns the folder browser. Cancelling it must preserve the current setting.
            val cancelled = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT_TREE),
                ActivityResult(Activity.RESULT_CANCELED, null), true)
            try {
                compose.onNodeWithText("输出文件夹").performScrollTo().performClick()
                compose.waitUntil(5_000) { cancelled.hits > 0 }
                compose.waitForIdle()
                assertEquals("", runBlocking { app.settingsRepository.defaults.first().outputDirectoryUri })
            } finally { instrumentation.removeMonitor(cancelled) }

            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${instrumentation.context.packageName}/app.ultraslim.FolderGrantActivity " +
                    "--es target ${app.packageName} --es treeId output --ez write true"
            ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            val selected = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT_TREE),
                ActivityResult(Activity.RESULT_OK, Intent().setData(tree)), true)
            try {
                compose.onNodeWithText("输出文件夹").performScrollTo().performClick()
                compose.waitUntil(5_000) {
                    runBlocking { app.settingsRepository.defaults.first().outputDirectoryUri == tree.toString() }
                }
                compose.waitForIdle()
                compose.onNodeWithText("自动保存到文件夹").assertExists()
                compose.onNodeWithText("恢复默认相册").performScrollTo().performClick()
                compose.waitUntil(5_000) {
                    runBlocking { app.settingsRepository.defaults.first().outputDirectoryUri.isBlank() }
                }
                compose.waitForIdle()
                compose.onNodeWithText("自动保存到相册").assertExists()
            } finally { instrumentation.removeMonitor(selected) }
        } finally {
            runBlocking { app.settingsRepository.setDefaults(original) }
            runCatching { app.contentResolver.releasePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
    }
}

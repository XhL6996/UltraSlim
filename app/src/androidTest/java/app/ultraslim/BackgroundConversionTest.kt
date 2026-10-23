package app.ultraslim

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import app.ultraslim.ui.home.HomeViewModel
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import app.ultraslim.convert.*
import app.ultraslim.data.*
import app.ultraslim.model.OutputFormat
import java.io.File
import java.util.UUID
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Real service + native encoding, with the activity hidden and the display asleep. */
class BackgroundConversionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = compose.activity.application as UltraSlimApp
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun waitFor(timeoutMillis: Long = 60_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for background service; queue=${app.conversionRepository.queue.value}" }
            SystemClock.sleep(100)
        }
    }

    private fun withBatch(action: (String) -> Unit) {
        val id = UUID.randomUUID().toString()
        val input = File(app.filesDir, "inputs/$id.jpg").apply { parentFile!!.mkdirs() }
        val image = Bitmap.createBitmap(768, 512, Bitmap.Config.ARGB_8888)
        image.eraseColor(0xff487abc.toInt())
        input.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 100, it) }; image.recycle()
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", input)
        runBlocking {
            app.settingsRepository.setAutoReplaceOriginals(false)
            app.conversionRepository.awaitReady()
            app.conversionRepository.addAll(listOf(QueueItem(id, uri.toString(), "$id.jpg", input.length(),
                status = QueueStatus.READY, batchId = id)))
            app.conversionRepository.flush()
            app.database.taskDao().putBatch(BatchEntity(id, "[\"$id\"]", ConversionDefaults(format = OutputFormat.AVIF,
                autoSave = false, onlySmaller = false, speedLevel = 2).encode()))
        }
        try { action(id) } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            runBlocking { app.database.taskDao().cancel(id) }
            waitFor { !app.conversionServiceRunning.value }
            runBlocking {
                app.database.historyDao().byTask(id)?.let {
                    File(it.outputUri).delete()
                    app.database.historyDao().deleteById(it.id)
                }
                app.conversionRepository.remove(id); app.conversionRepository.flush()
            }
            input.delete()
        }
    }

    private fun startAndHide(id: String, sleep: Boolean = false) {
        compose.runOnUiThread { ConversionService.start(app, id) }
        waitFor { shell("dumpsys activity services ${app.packageName}").contains("isForeground=true") }
        shell("input keyevent KEYCODE_HOME")
        if (sleep) {
            shell("input keyevent KEYCODE_SLEEP")
            assertFalse(app.getSystemService(PowerManager::class.java).isInteractive)
        }
    }

    @Test fun encodingCompletesWithActivityHiddenAndScreenOffWithoutAJob() = withBatch { id ->
        // Hold the real encoding mutex so the activity definitely leaves before any pixels encode.
        runBlocking { app.conversionMutex.lock() }
        try { startAndHide(id, sleep = true) } finally { app.conversionMutex.unlock() }
        waitFor { !app.conversionServiceRunning.value }
        assertEquals(QueueStatus.DONE, app.conversionRepository.find(id)?.status)
        val history = runBlocking { app.database.historyDao().byTask(id) }!!
        assertTrue(File(history.outputUri).isFile)
        assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork(ConversionWorker.UNIQUE_WORK_NAME)
            .get().all { it.state.isFinished })
        assertFalse(shell("dumpsys power").substringAfter("Wake Locks:").substringBefore("Suspend Blockers:").contains("UltraSlim:conversion"))
    }

    @Test fun systemTimeoutStopsServiceAndKeepsQueueResumable() {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val key = "media_processing_fgs_timeout_duration"
        val previous = shell("device_config get activity_manager $key").trim()
        shell("device_config put activity_manager $key 1500")
        try {
            withBatch { id ->
                runBlocking { app.conversionMutex.lock() }
                try {
                    startAndHide(id)
                    waitFor { !app.conversionServiceRunning.value }
                    val item = app.conversionRepository.find(id)!!
                    assertEquals(QueueStatus.READY, item.status)
                    assertTrue(item.error.orEmpty(), item.error.orEmpty().contains("后台处理时限"))
                    assertFalse(shell("dumpsys activity services ${app.packageName}").contains("isForeground=true"))
                    assertNull(runBlocking { app.database.historyDao().byTask(id) })
                } finally { app.conversionMutex.unlock() }
            }
        } finally {
            if (previous == "null") shell("device_config delete activity_manager $key")
            else shell("device_config put activity_manager $key $previous")
        }
    }

    @Test fun cancellingLegacyBackoffDoesNotWaitForItsScheduler() = withBatch { id ->
        val manager = WorkManager.getInstance(app)
        val request = OneTimeWorkRequestBuilder<ConversionWorker>()
            .setInitialDelay(10, TimeUnit.MINUTES)
            .setInputData(workDataOf(ConversionWorker.KEY_BATCH to id)).build()
        manager.enqueueUniqueWork(ConversionWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request).result.get()
        val vm = HomeViewModel(app)
        val store = androidx.lifecycle.ViewModelStore().apply { put("background-test", vm) }
        val observer = CoroutineScope(Dispatchers.Main + SupervisorJob())
        observer.launch { vm.uiState.collect {} }
        try {
            waitFor { vm.uiState.value.isConverting }
            compose.runOnUiThread { vm.cancelConversion() }
            waitFor { manager.getWorkInfoById(request.id).get()?.state == WorkInfo.State.CANCELLED }
            assertTrue(runBlocking { app.database.taskDao().batch(id) }!!.cancelled)
            assertEquals(QueueStatus.READY, app.conversionRepository.find(id)?.status)
        } finally {
            observer.cancel()
            compose.runOnUiThread { store.clear() }
            manager.cancelWorkById(request.id).result.get()
        }
    }

    @Test fun thermalPauseResumesInBackgroundWithoutRestartingService() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        shell("cmd thermalservice override-status 3")
        try {
            withBatch { id ->
                startAndHide(id)
                waitFor { app.conversionThermalState.value?.phase == ThermalPhase.COOLING }
                assertTrue(app.conversionServiceRunning.value)
                waitFor { !shell("dumpsys power").substringAfter("Wake Locks:").substringBefore("Suspend Blockers:").contains("UltraSlim:conversion") }
                val cooledAt = SystemClock.elapsedRealtime()
                shell("cmd thermalservice reset")
                waitFor { !app.conversionServiceRunning.value }
                assertEquals(QueueStatus.DONE, app.conversionRepository.find(id)?.status)
                assertTrue("Cooling recovery must not wait through the old long backoff", SystemClock.elapsedRealtime() - cooledAt < 30_000)
            }
        } finally { shell("cmd thermalservice reset") }
    }

    @Test fun sustainedSevereStatusStopsWaitingAndKeepsManualResumeAvailable() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        shell("cmd thermalservice override-status 3")
        try {
            withBatch { id ->
                compose.runOnUiThread { ConversionService.start(app, id) }
                waitFor { app.conversionThermalState.value?.phase == ThermalPhase.COOLING }
                compose.waitUntil(5000) {
                    compose.onAllNodesWithTag("conversion-thermal-status").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("conversion-thermal-status").assertIsDisplayed()
                compose.onNodeWithTag("conversion-spinner").assertDoesNotExist()
                waitFor(timeoutMillis = 120_000) { !app.conversionServiceRunning.value }
                val item = app.conversionRepository.find(id)!!
                assertEquals(QueueStatus.READY, item.status)
                assertTrue(item.error.orEmpty().contains("系统报告"))
                assertTrue(item.error.orEmpty().contains("点击继续"))
                assertEquals(ThermalPhase.PAUSED, app.conversionThermalState.value?.phase)
                assertNull(runBlocking { app.database.historyDao().byTask(id) })
                assertFalse(shell("dumpsys activity services ${app.packageName}").contains("isForeground=true"))
            }
        } finally {
            app.conversionThermalState.value = null
            shell("cmd thermalservice reset")
        }
    }

    @Test fun cancellingCoolingDoesNotWaitForThermalBackoff() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        shell("cmd thermalservice override-status 3")
        try {
            withBatch { id ->
                startAndHide(id)
                waitFor { app.conversionThermalState.value?.phase == ThermalPhase.COOLING }
                runBlocking { app.database.taskDao().cancel(id) }
                waitFor(timeoutMillis = 5000) { !app.conversionServiceRunning.value }
                assertEquals(QueueStatus.READY, app.conversionRepository.find(id)?.status)
                assertNull(app.conversionThermalState.value)
            }
        } finally { shell("cmd thermalservice reset") }
    }

    @Test fun clearingCoolingQueueStopsServiceWithoutWaitingForBackoff() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        shell("cmd thermalservice override-status 3")
        try {
            withBatch { id ->
                startAndHide(id)
                waitFor { app.conversionThermalState.value?.phase == ThermalPhase.COOLING }
                // Wait for the longer backoff so this catches waiting until the next runner pass.
                waitFor { app.conversionThermalState.value?.remainingSeconds?.let { it in 85..89 } == true }
                app.conversionRepository.remove(id)
                waitFor(timeoutMillis = 5000) { !app.conversionServiceRunning.value }
                assertNull(app.conversionThermalState.value)
                assertNull(app.conversionRepository.find(id))
                assertNull(runBlocking { app.database.historyDao().byTask(id) })
                assertFalse(shell("dumpsys activity services ${app.packageName}").contains("isForeground=true"))
            }
        } finally { shell("cmd thermalservice reset") }
    }
}

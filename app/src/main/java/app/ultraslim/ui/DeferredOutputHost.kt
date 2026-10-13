package app.ultraslim.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.work.WorkManager
import app.ultraslim.UltraSlimApp
import app.ultraslim.convert.AutoReplacement
import app.ultraslim.convert.ConversionWorker
import app.ultraslim.convert.OutputStore
import app.ultraslim.data.decodeDefaults
import kotlinx.coroutines.flow.combine

/** Small-window/background encoding stays private until the user returns to the full app. */
@Composable
internal fun DeferredOutputHost() {
    val app = LocalContext.current.applicationContext as UltraSlimApp
    LaunchedEffect(app) {
        combine(app.database.historyDao().pendingPublications(), app.galleryPublishingAllowed,
            app.conversionServiceRunning,
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ConversionWorker.UNIQUE_WORK_NAME)) { rows, allowed, service, work ->
            rows to (allowed && !service && work.none { !it.state.isFinished })
        }.collect { (rows, allowed) ->
            if (!allowed || rows.isEmpty()) return@collect
            app.galleryPublicationRunning.value = true
            try {
                for (row in rows) {
                    if (!app.galleryPublishingAllowed.value || app.conversionServiceRunning.value) break
                    val batch = app.database.taskDao().batch(row.batchId) ?: continue
                    val options = runCatching { decodeDefaults(batch.settings) }.getOrNull() ?: continue
                    if (batch.cancelled || !options.autoSave) continue
                    try {
                        val saved = OutputStore.save(app, row)
                        if (options.autoReplaceOriginals) AutoReplacement.saved(app, saved)
                        row.taskId?.let { id -> app.conversionRepository.update(id) {
                            it.copy(outputUri = saved.outputUri, error = if (saved.saveState == "SAVED") null else "保存失败，可在详情重试")
                        } }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        // A deleted history row or lost provider must not crash the foreground app.
                        android.util.Log.w("DeferredOutput", "Publication failed", e)
                        app.database.historyDao().byId(row.id)?.let {
                            app.database.historyDao().update(it.copy(saveState = "FAILED"))
                        }
                    }
                }
            } finally { app.galleryPublicationRunning.value = false }
        }
    }
}

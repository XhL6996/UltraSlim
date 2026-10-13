package app.ultraslim.ui

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkManager
import app.ultraslim.UltraSlimApp
import app.ultraslim.convert.AutoReplacement
import app.ultraslim.convert.ConversionWorker
import app.ultraslim.convert.MediaStoreTrashHelper
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs only while foreground, after the conversion batch stops. System confirmation remains authoritative. */
@Composable
internal fun AutoReplacementHost() {
    if (android.os.Build.VERSION.SDK_INT < 30) return
    val app = LocalContext.current.applicationContext as UltraSlimApp
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var activeIds by rememberSaveable { mutableStateOf(longArrayOf()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val ids = activeIds.toList()
        scope.launch {
            val rows = ids.mapNotNull { app.database.replacementDao().byId(it) }
            val confirmed = result.resultCode == Activity.RESULT_OK
            if (!confirmed) {
                val remaining = app.database.replacementDao().ready().first()
                AutoReplacement.finish(app, remaining, "CANCELLED", "已取消待处理替换，原图保留")
            }
            val trashed = withContext(Dispatchers.IO) {
                rows.filter { confirmed && MediaStoreTrashHelper.isOriginalTrashed(app, Uri.parse(it.sourceUri)) }
            }
            AutoReplacement.finish(app, trashed, "DONE", "原图已移入系统回收站")
            AutoReplacement.finish(app, rows - trashed.toSet(), "CANCELLED", "未确认替换，原图保留")
            activeIds = longArrayOf()
            withContext(Dispatchers.Main) {
                Toast.makeText(app, if (trashed.isEmpty()) "未完成替换，原图保留" else "${trashed.size} 张原图已移入系统回收站", Toast.LENGTH_LONG).show()
            }
        }
    }
    LaunchedEffect(owner) {
        app.awaitReplacementRecovery()
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val blocked = combine(app.galleryPublishingAllowed, app.galleryPublicationRunning) { allowed, publishing -> !allowed || publishing }
            combine(app.database.replacementDao().ready(), app.settingsRepository.defaults,
                WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ConversionWorker.UNIQUE_WORK_NAME),
                snapshotFlow { activeIds.isNotEmpty() },
                combine(app.conversionServiceRunning, blocked) { service, blocked -> service || blocked }) { rows, defaults, work, awaitingResult, service ->
                Triple(rows, defaults.autoReplaceOriginals, awaitingResult || service || work.any { !it.state.isFinished })
            }.collect { (rows, enabled, busy) ->
                if (rows.isEmpty()) return@collect
                if (!enabled) {
                    AutoReplacement.finish(app, rows, "CANCELLED", "自动替换已关闭，原图保留")
                    return@collect
                }
                if (busy || activeIds.isNotEmpty()) return@collect
                // Resolve external deletions before hashing/decoding. Already trashed files may still be readable.
                val verified = mutableListOf<app.ultraslim.data.ReplacementEntity>()
                for (row in rows) {
                    if (!app.galleryPublishingAllowed.value || app.galleryPublicationRunning.value || app.conversionServiceRunning.value) return@collect
                    val state = withContext(Dispatchers.IO) { MediaStoreTrashHelper.originalState(app, Uri.parse(row.sourceUri)) }
                    when (state) {
                        MediaStoreTrashHelper.OriginalState.TRASHED -> AutoReplacement.finish(app, listOf(row), "DONE", "原图已在系统回收站，无需再次确认")
                        MediaStoreTrashHelper.OriginalState.UNAVAILABLE -> AutoReplacement.finish(app, listOf(row), "SKIPPED", "原图已删除或不可访问，不再请求替换")
                        MediaStoreTrashHelper.OriginalState.PRESENT -> try { AutoReplacement.validate(app, row); verified += row }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { AutoReplacement.finish(app, listOf(row), "SKIPPED", "原图保留：${e.message ?: "校验未通过"}") }
                    }
                    if (verified.map { it.sourceUri }.distinct().size >= MediaStoreTrashHelper.MAX_REQUEST_ITEMS) break
                }
                if (verified.isEmpty()) return@collect
                // Cancellation before launch leaves READY rows retryable on the next foreground entry.
                try {
                    val request = withContext(Dispatchers.IO) { MediaStoreTrashHelper.prepareTrashRequest(app, verified.map { Uri.parse(it.sourceUri) }) }
                    val sender = request.sender ?: error(request.message ?: "无法创建回收站请求")
                    val requested = verified.filter { Uri.parse(it.sourceUri) in request.uris }
                    AutoReplacement.finish(app, verified - requested.toSet(), "SKIPPED", "原图状态已变化，不再请求替换")
                    if (!app.galleryPublishingAllowed.value || app.galleryPublicationRunning.value || app.conversionServiceRunning.value || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@collect
                    // The system dialog pauses our activity. Do not let that cancellation lose its result ids.
                    withContext(NonCancellable + Dispatchers.Main) {
                        activeIds = requested.map { it.historyId }.toLongArray()
                        app.database.replacementDao().finish(activeIds.toList(), "REQUESTED", "等待系统确认")
                        launcher.launch(IntentSenderRequest.Builder(sender).build())
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    activeIds = longArrayOf()
                    AutoReplacement.finish(app, verified, "SKIPPED", "${e.message ?: "无法发起系统确认"}，原图保留")
                }
            }
        }
    }
}

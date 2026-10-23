package app.ultraslim.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.shareIn

/** Shares one aggregate query across the home and history screens. */
@OptIn(FlowPreview::class)
class HistoryRepository(database: AppDatabase, scope: CoroutineScope) {
    private val dao = database.historyDao()

    // Shared by deferred publication and replacement review; never load every pending image
    // just to decide whether publication is allowed. Do not distinctUntilChanged this signal:
    // new pending rows in an existing batch must wake a publisher that has just drained it.
    val pendingPublicationBatches = dao.pendingPublicationBatches()
        .map { batches -> batches.filter {
            runCatching { decodeDefaults(it.settings).autoSave }.getOrDefault(false)
        } }
        .conflate()
        .shareIn(scope, SharingStarted.WhileSubscribed(0L, replayExpirationMillis = 0L), replay = 1)

    // Sample invalidations BEFORE querying: sampling a DAO Flow still runs SQL on each write.
    // emitInitialState also refreshes on resubscription, including writes made while inactive.
    // Room's invalidation flow stays open, so the last write in a burst reaches the next tick.
    // map (rather than mapLatest) lets a query finish even during continuous writes; scope/
    // WhileSubscribed cancellation still cancels the suspend Room query and its observer.
    val stats: StateFlow<HistoryStats> = database.invalidationTracker
        .createFlow("history", emitInitialState = true)
        .sample(500L)
        .map { dao.stats() }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), HistoryStats())
}

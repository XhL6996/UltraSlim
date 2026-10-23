package app.ultraslim.convert

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.ultraslim.UltraSlimApp
import kotlinx.coroutines.sync.withLock

/** Compatibility adapter for batches already scheduled before the direct-service upgrade. */
class ConversionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val UNIQUE_WORK_NAME = "conversion"
        const val CHANNEL_ID = "conversion"
        const val KEY_BATCH = "batch_id"
    }
    private val runner = ConversionRunner(applicationContext) { setForeground(it) }
    override suspend fun getForegroundInfo() = runner.getForegroundInfo()
    override suspend fun doWork(): Result {
        val batch = inputData.getString(KEY_BATCH) ?: return Result.failure()
        return (applicationContext as UltraSlimApp).conversionMutex.withLock {
            when (runner.run(batch, runAttemptCount)) {
                ConversionRunner.Outcome.COMPLETE -> Result.success()
                ConversionRunner.Outcome.FAILED, ConversionRunner.Outcome.PAUSED -> Result.failure()
                ConversionRunner.Outcome.COOLING -> {
                    // Legacy WorkManager backoff can leave a queued batch looking active for hours.
                    // Stop the legacy job and let the user resume through the direct service.
                    runner.pauseCooling()
                    Result.failure()
                }
            }
        }
    }
}

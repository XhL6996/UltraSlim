package app.ultraslim

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.room.Room
import app.ultraslim.convert.ConversionRepository
import app.ultraslim.convert.ConversionWorker
import app.ultraslim.data.AppDatabase
import app.ultraslim.data.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UltraSlimApp : Application() {

    // Shared with legacy workers to prevent overlapping native encoders during an upgrade.
    internal val conversionMutex = kotlinx.coroutines.sync.Mutex()
    internal val conversionServiceRunning = kotlinx.coroutines.flow.MutableStateFlow(false)

    private val replacementRecovery = kotlinx.coroutines.CompletableDeferred<Unit>()
    suspend fun awaitReplacementRecovery() = replacementRecovery.await()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Room.databaseBuilder(this, AppDatabase::class.java, "ultraslim.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .build()
    }

    val settingsRepository: SettingsRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SettingsRepository(this)
    }

    val conversionRepository: ConversionRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ConversionRepository(database.taskDao(), appScope)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        appScope.launch {
            // A process death while the system dialog was open must not re-prompt indefinitely.
            database.replacementDao().interruptRequests()
            replacementRecovery.complete(Unit)
            conversionRepository.awaitReady()
            // Promote legacy cache outputs before any cleanup; never delete a referenced result.
            database.historyDao().all().forEach { entity ->
                if (!entity.outputUri.startsWith("content://")) {
                    val old = File(entity.outputUri)
                    if (old.isFile && old.canonicalPath.startsWith(cacheDir.canonicalPath + "/")) {
                        val target = File(filesDir, "outputs/${entity.id}_${old.name}").apply { parentFile?.mkdirs() }
                        if (old.renameTo(target)) database.historyDao().update(entity.copy(outputUri = target.path))
                    }
                }
            }
            val referenced = database.historyDao().all().flatMap { listOf(it.inputUri, it.outputUri) } + conversionRepository.queue.value.flatMap { listOf(it.uri, it.outputUri.orEmpty()) }
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            listOf("inputs", "outputs").forEach { folder ->
                File(filesDir, folder).listFiles()?.forEach { file ->
                    val uri = androidx.core.content.FileProvider.getUriForFile(this@UltraSlimApp, "$packageName.files", file).toString()
                    if(file.lastModified() < cutoff && file.path !in referenced && uri !in referenced) file.delete()
                }
            }
            cleanStaleOutputs()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            ConversionWorker.CHANNEL_ID,
            getString(R.string.channel_conversion_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_conversion_desc)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** Drops leftover transcode outputs and URI batches older than a day. */
    private fun cleanStaleOutputs() {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        for (name in listOf("previews")) {
            runCatching {
                val dir = File(cacheDir, name)
                if (!dir.isDirectory) return@runCatching
                dir.listFiles()?.forEach { file ->
                    if (file.lastModified() < cutoff) file.delete()
                }
            }
        }
    }
}

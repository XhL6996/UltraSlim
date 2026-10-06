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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Room.databaseBuilder(this, AppDatabase::class.java, "ultraslim.db").build()
    }

    val settingsRepository: SettingsRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SettingsRepository(this)
    }

    val conversionRepository: ConversionRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ConversionRepository()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        appScope.launch { cleanStaleOutputs() }
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
        for (name in listOf("outputs", "batches")) {
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

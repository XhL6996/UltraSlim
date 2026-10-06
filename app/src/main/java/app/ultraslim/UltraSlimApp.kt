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

    /** Drops transcode outputs older than a day that were never saved to the album. */
    private fun cleanStaleOutputs() {
        runCatching {
            val dir = File(cacheDir, "outputs")
            if (!dir.isDirectory) return
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            dir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) file.delete()
            }
        }
    }
}

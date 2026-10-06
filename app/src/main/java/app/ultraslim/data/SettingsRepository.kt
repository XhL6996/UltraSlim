package app.ultraslim.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.ultraslim.model.ConversionSettings
import app.ultraslim.model.OutputFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

data class ThemeSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
)

/** Persisted conversion defaults (ConversionSettings + 自动保存到相册). */
data class ConversionDefaults(
    val format: OutputFormat = OutputFormat.AVIF,
    val quality: Int = 90,
    val gainmapQuality: Int = 60,
    val gainmapScale: Int = 4,
    val multiChannelGainmap: Boolean = true,
    val keepExif: Boolean = true,
    val allowSdr: Boolean = true,
    val autoSave: Boolean = true,
    val speedLevel: Int = 1,
    val resizePercent: Int = 100,
) {
    fun toConversionSettings(): ConversionSettings =
        ConversionSettings(
            format = format,
            quality = quality,
            gainmapQuality = gainmapQuality,
            gainmapScale = gainmapScale,
            multiChannelGainmap = multiChannelGainmap,
            keepExif = keepExif,
            allowSdr = allowSdr,
            speedLevel = speedLevel,
            resizePercent = resizePercent,
        )
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val OUTPUT_FORMAT = stringPreferencesKey("output_format")
        val QUALITY = intPreferencesKey("quality")
        val GAINMAP_QUALITY = intPreferencesKey("gainmap_quality")
        val GAINMAP_SCALE = intPreferencesKey("gainmap_scale")
        val MULTI_CHANNEL = booleanPreferencesKey("multi_channel_gainmap")
        val KEEP_EXIF = booleanPreferencesKey("keep_exif")
        val ALLOW_SDR = booleanPreferencesKey("allow_sdr_transcode")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val AUTO_SAVE = booleanPreferencesKey("auto_save")
        val SPEED_LEVEL = intPreferencesKey("speed_level")
        val RESIZE_PERCENT = intPreferencesKey("resize_percent")
    }

    val defaults: Flow<ConversionDefaults> = context.settingsDataStore.data.map { p ->
        ConversionDefaults(
            format = OutputFormat.parse(p[Keys.OUTPUT_FORMAT]),
            quality = p[Keys.QUALITY] ?: 90,
            gainmapQuality = p[Keys.GAINMAP_QUALITY] ?: 60,
            gainmapScale = p[Keys.GAINMAP_SCALE] ?: 4,
            multiChannelGainmap = p[Keys.MULTI_CHANNEL] ?: true,
            keepExif = p[Keys.KEEP_EXIF] ?: true,
            allowSdr = p[Keys.ALLOW_SDR] ?: true,
            autoSave = p[Keys.AUTO_SAVE] ?: true,
            speedLevel = p[Keys.SPEED_LEVEL] ?: 1,
            resizePercent = p[Keys.RESIZE_PERCENT] ?: 100,
        )
    }

    val theme: Flow<ThemeSettings> = context.settingsDataStore.data.map { p ->
        ThemeSettings(
            themeMode = ThemeMode.fromId(p[Keys.THEME_MODE]),
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: true,
        )
    }

    suspend fun setOutputFormat(format: OutputFormat) {
        context.settingsDataStore.edit { it[Keys.OUTPUT_FORMAT] = format.extension }
    }

    suspend fun setQuality(quality: Int) {
        context.settingsDataStore.edit { it[Keys.QUALITY] = quality.coerceIn(50, 100) }
    }

    suspend fun setGainmapQuality(quality: Int) {
        context.settingsDataStore.edit { it[Keys.GAINMAP_QUALITY] = quality.coerceIn(30, 90) }
    }

    suspend fun setGainmapScale(scale: Int) {
        context.settingsDataStore.edit { it[Keys.GAINMAP_SCALE] = scale }
    }

    suspend fun setMultiChannelGainmap(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.MULTI_CHANNEL] = enabled }
    }

    suspend fun setKeepExif(keep: Boolean) {
        context.settingsDataStore.edit { it[Keys.KEEP_EXIF] = keep }
    }

    suspend fun setAllowSdr(allow: Boolean) {
        context.settingsDataStore.edit { it[Keys.ALLOW_SDR] = allow }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.id }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setAutoSave(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_SAVE] = enabled }
    }

    suspend fun setSpeedLevel(level: Int) {
        context.settingsDataStore.edit { it[Keys.SPEED_LEVEL] = level.coerceIn(0, 2) }
    }

    suspend fun setResizePercent(percent: Int) {
        context.settingsDataStore.edit { it[Keys.RESIZE_PERCENT] = percent.coerceIn(25, 100) }
    }
}

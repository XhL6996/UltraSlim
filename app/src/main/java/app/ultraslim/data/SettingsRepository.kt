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
    val quality: Int = 92,
    val gainmapQuality: Int = 60,
    val gainmapScale: Int = 4,
    val multiChannelGainmap: Boolean = true,
    val keepExif: Boolean = true,
    val allowSdr: Boolean = true,
    val autoSave: Boolean = true,
    val onlySmaller: Boolean = true,
    val keepLocation: Boolean = true,
    val speedLevel: Int = 1,
    val resizePercent: Int = 100,
    val avifGainmapCompatibility: Boolean = true,
) {
    fun toConversionSettings(): ConversionSettings =
        ConversionSettings(
            format = format,
            quality = quality,
            gainmapQuality = gainmapQuality,
            gainmapScale = gainmapScale,
            multiChannelGainmap = multiChannelGainmap,
            keepExif = keepExif,
            keepLocation = keepLocation,
            allowSdr = allowSdr,
            speedLevel = speedLevel,
            resizePercent = resizePercent,
            avifGainmapCompatibility = avifGainmapCompatibility,
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
        val ONLY_SMALLER = booleanPreferencesKey("only_smaller")
        val KEEP_LOCATION = booleanPreferencesKey("keep_location")
        val SPEED_LEVEL = intPreferencesKey("speed_level")
        val RESIZE_PERCENT = intPreferencesKey("resize_percent")
        val AVIF_GAINMAP_COMPATIBILITY = booleanPreferencesKey("avif_gainmap_compatibility")
    }

    val defaults: Flow<ConversionDefaults> = context.settingsDataStore.data.map { p ->
        ConversionDefaults(
            format = OutputFormat.parse(p[Keys.OUTPUT_FORMAT]),
            quality = p[Keys.QUALITY] ?: 92,
            gainmapQuality = p[Keys.GAINMAP_QUALITY] ?: 60,
            gainmapScale = p[Keys.GAINMAP_SCALE] ?: 4,
            multiChannelGainmap = p[Keys.MULTI_CHANNEL] ?: true,
            keepExif = p[Keys.KEEP_EXIF] ?: true,
            allowSdr = p[Keys.ALLOW_SDR] ?: true,
            autoSave = p[Keys.AUTO_SAVE] ?: true,
            onlySmaller = p[Keys.ONLY_SMALLER] ?: true,
            keepLocation = p[Keys.KEEP_LOCATION] ?: true,
            speedLevel = p[Keys.SPEED_LEVEL] ?: 1,
            resizePercent = p[Keys.RESIZE_PERCENT] ?: 100,
            avifGainmapCompatibility = p[Keys.AVIF_GAINMAP_COMPATIBILITY] ?: true,
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

    suspend fun setOnlySmaller(value: Boolean) { context.settingsDataStore.edit { it[Keys.ONLY_SMALLER] = value } }
    suspend fun setKeepLocation(value: Boolean) { context.settingsDataStore.edit { it[Keys.KEEP_LOCATION] = value } }

    suspend fun setSpeedLevel(level: Int) {
        context.settingsDataStore.edit { it[Keys.SPEED_LEVEL] = level.coerceIn(0, 2) }
    }

    suspend fun setResizePercent(percent: Int) {
        context.settingsDataStore.edit { it[Keys.RESIZE_PERCENT] = percent.coerceIn(25, 100) }
    }

    suspend fun setAvifGainmapCompatibility(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AVIF_GAINMAP_COMPATIBILITY] = enabled }
    }

    suspend fun setDefaults(d: ConversionDefaults) {
        context.settingsDataStore.edit { p ->
            p[Keys.OUTPUT_FORMAT] = d.format.extension
            p[Keys.QUALITY] = d.quality.coerceIn(50, 100)
            p[Keys.GAINMAP_QUALITY] = d.gainmapQuality.coerceIn(30, 90)
            p[Keys.GAINMAP_SCALE] = d.gainmapScale
            p[Keys.MULTI_CHANNEL] = d.multiChannelGainmap
            p[Keys.KEEP_EXIF] = d.keepExif
            p[Keys.ALLOW_SDR] = d.allowSdr
            p[Keys.AUTO_SAVE] = d.autoSave
            p[Keys.ONLY_SMALLER] = d.onlySmaller
            p[Keys.KEEP_LOCATION] = d.keepLocation
            p[Keys.SPEED_LEVEL] = d.speedLevel.coerceIn(0, 2)
            p[Keys.RESIZE_PERCENT] = d.resizePercent.coerceIn(25, 100)
            p[Keys.AVIF_GAINMAP_COMPATIBILITY] = d.avifGainmapCompatibility
        }
    }
}

fun ConversionDefaults.encode(): String = org.json.JSONObject().apply {
    put("format", format.extension); put("quality", quality); put("gainmapQuality", gainmapQuality)
    put("gainmapScale", gainmapScale); put("multiChannel", multiChannelGainmap); put("keepExif", keepExif)
    put("allowSdr", allowSdr); put("autoSave", autoSave); put("speed", speedLevel); put("resize", resizePercent)
    put("onlySmaller", onlySmaller); put("keepLocation", keepLocation)
    put("avifGainmapCompatibility", avifGainmapCompatibility)
}.toString()

fun decodeDefaults(value: String): ConversionDefaults {
    val j = org.json.JSONObject(value)
    return ConversionDefaults(format = OutputFormat.parse(j.getString("format")), quality = j.getInt("quality"),
        gainmapQuality = j.getInt("gainmapQuality"), gainmapScale = j.getInt("gainmapScale"),
        multiChannelGainmap = j.getBoolean("multiChannel"), keepExif = j.getBoolean("keepExif"),
        allowSdr = j.getBoolean("allowSdr"), autoSave = j.getBoolean("autoSave"), speedLevel = j.getInt("speed"),
        resizePercent = j.getInt("resize"), onlySmaller = j.optBoolean("onlySmaller", true), keepLocation = j.optBoolean("keepLocation", true),
        // Old queued jobs and history used pixel-exact encoding. Do not relabel them.
        avifGainmapCompatibility = j.optBoolean("avifGainmapCompatibility", false))
}

fun ConversionDefaults.summary(): String = "${format.label} · 编码质量 $quality · ${if (resizePercent == 100) "原始尺寸" else "尺寸 $resizePercent%"} · ${if (format == OutputFormat.AVIF && avifGainmapCompatibility) "HDR 相册兼容" else "Gain Map 原样保留"} · ${if (autoSave) "自动保存" else "手动保存"}"

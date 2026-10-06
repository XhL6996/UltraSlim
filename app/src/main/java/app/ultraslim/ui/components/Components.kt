package app.ultraslim.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.ultraslim.data.ConversionDefaults
import app.ultraslim.model.OutputFormat
import app.ultraslim.util.formatBytes
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coil model for a string that is either a content uri or a cache file path. */
fun imageModelOf(uriString: String): Any =
    if (uriString.startsWith("content://") || uriString.startsWith("android.resource://")) {
        Uri.parse(uriString)
    } else {
        File(uriString)
    }

@Composable
fun SectionHeader(title: String, hint: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun StatsCard(totalSavedBytes: Long, convertedCount: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (totalSavedBytes >= 0) "输出体积减少" else "输出体积增加",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
                Text(
                    formatBytes(kotlin.math.abs(totalSavedBytes)),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "已转换 · 原图保留",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
                Text(
                    "$convertedCount",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun BadgeChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.small, color = containerColor) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor,
        )
    }
}

@Composable
fun SwitchRow(
    title: String,
    hint: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Callbacks for every control of [ConversionOptionsPanel]. */
class OptionsCallbacks(
    val onFormatChange: (OutputFormat) -> Unit,
    val onQualityChange: (Int) -> Unit,
    val onGainmapQualityChange: (Int) -> Unit,
    val onGainmapScaleChange: (Int) -> Unit,
    val onMultiChannelChange: (Boolean) -> Unit,
    val onKeepExifChange: (Boolean) -> Unit,
    val onAllowSdrChange: (Boolean) -> Unit,
    val onAutoSaveChange: (Boolean) -> Unit,
    val onPreset: (CompressionPreset) -> Unit,
    val onSpeedLevelChange: (Int) -> Unit,
    val onResizePercentChange: (Int) -> Unit,
    val onOnlySmallerChange: (Boolean) -> Unit = {},
    val onKeepLocationChange: (Boolean) -> Unit = {},
)

/** Named bundles of conversion parameters shown as quick-select chips. */
enum class CompressionPreset(
    val label: String,
    val quality: Int,
    val gainmapQuality: Int,
    val gainmapScale: Int,
    val resizePercent: Int,
    val speedLevel: Int,
) {
    QUALITY("细节优先", quality = 92, gainmapQuality = 70, gainmapScale = 2, resizePercent = 100, speedLevel = 0),
    BALANCED("推荐", quality = 82, gainmapQuality = 60, gainmapScale = 4, resizePercent = 100, speedLevel = 1),
    EXTREME("体积优先", quality = 65, gainmapQuality = 60, gainmapScale = 4, resizePercent = 100, speedLevel = 0),
    ;

    fun matches(defaults: ConversionDefaults): Boolean =
        defaults.quality == quality &&
            defaults.resizePercent == resizePercent &&
            defaults.speedLevel == speedLevel
}

/**
 * The conversion options card, shared by the home screen and the settings
 * screen. Every change is persisted through the supplied callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConversionOptionsPanel(
    defaults: ConversionDefaults,
    callbacks: OptionsCallbacks,
    modifier: Modifier = Modifier,
    showAutoSave: Boolean = true,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader("输出格式", "体积和 HDR 显示支持取决于图片、设备与查看应用")
            val formats = OutputFormat.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                formats.forEachIndexed { index, format ->
                    SegmentedButton(
                        selected = defaults.format == format,
                        onClick = { callbacks.onFormatChange(format) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = formats.size),
                    ) {
                        Text(format.label)
                    }
                }
            }

            SectionHeader("主图压缩", "只调整主图；Gain Map 保留原始尺寸、通道与参数")
            val matchedPreset = CompressionPreset.entries.firstOrNull { it.matches(defaults) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompressionPreset.entries.forEach { preset ->
                    FilterChip(selected = matchedPreset == preset, onClick = { callbacks.onPreset(preset) }, label = { Text(preset.label) })
                }
            }
            SliderOption("编码质量", defaults.quality, 50f..100f, 0, callbacks.onQualityChange)
            Text(
                if (defaults.quality >= 95) "高质量重编码可能比原图更大。100 是编码器档位，不代表无损或保留原图 100% 画质。"
                else "数值越高通常体积越大。不同格式的同一数值不等价，建议先试转一张。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Gain Map · 跟随原图", style = MaterialTheme.typography.titleSmall)
                    Text(if (defaults.format == OutputFormat.JPEG) "直接复用 JPEG 原始增益图；其他 HDR 输入请选择 HEIC / AVIF。"
                        else "增益图独立无损编码，不缩小、不重建；方向与主图同步。", style = MaterialTheme.typography.bodySmall)
                }
            }
            SwitchRow("仅保留更小结果", "没有节省空间时跳过，原图始终保留", defaults.onlySmaller, callbacks.onOnlySmallerChange)
            var advanced by remember { mutableStateOf(false) }
            androidx.compose.material3.TextButton(onClick = { advanced = !advanced }) { Text(if(advanced) "收起更多选项" else "尺寸、速度与元数据") }
            if(advanced) {
            SectionHeader("编码速度", "更快编码可能增加体积或影响细节，建议先试转")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "画质", 1 to "平衡", 2 to "速度").forEach { (level, label) ->
                    FilterChip(
                        selected = defaults.speedLevel == level,
                        onClick = { callbacks.onSpeedLevelChange(level) },
                        label = { Text(label) },
                    )
                }
            }

            SectionHeader("分辨率", "缩小分辨率可大幅减小体积")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(100, 75, 50).forEach { percent ->
                    FilterChip(
                        selected = defaults.resizePercent == percent,
                        onClick = { callbacks.onResizePercentChange(percent) },
                        label = { Text("$percent%") },
                    )
                }
            }

            SwitchRow(
                title = "保留 EXIF",
                hint = "保留拍摄时间、地点等元数据",
                checked = defaults.keepExif,
                onCheckedChange = callbacks.onKeepExifChange,
            )
            SwitchRow("保留位置数据", "关闭后移除 EXIF GPS 信息", defaults.keepLocation, callbacks.onKeepLocationChange)
            SwitchRow(
                title = "允许纯 SDR 转换",
                hint = "没有 Gain Map 的图片也进行转码",
                checked = defaults.allowSdr,
                onCheckedChange = callbacks.onAllowSdrChange,
            )
            }
            if (showAutoSave) {
                SwitchRow(
                    title = "自动保存到相册",
                    hint = "转换完成后写入 相册/UltraSlim",
                    checked = defaults.autoSave,
                    onCheckedChange = callbacks.onAutoSaveChange,
                )
            }
        }
    }
}

@Composable
private fun SliderOption(
    label: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Int) -> Unit,
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${sliderValue.roundToInt()}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = { onChange(sliderValue.roundToInt()) },
        )
    }
}

/** Long edge for a 56.dp row thumbnail: two device pixels, clamped for the native decoder. */
@Composable
fun rememberThumbMaxDim(): Int {
    val density = LocalDensity.current
    return remember(density) {
        with(density) { (56.dp.toPx() * 2f).roundToInt() }.coerceIn(64, 512)
    }
}

/**
 * AsyncImage with a native-decode fallback: when the platform decoder cannot
 * handle the file (e.g. ISO 21496-1 gain map HEIC), the primary SDR rendition
 * is decoded by the bundled native stack and shown as JPEG bytes instead.
 * [maxDim] is the long edge requested from that fallback. List rows should pass
 * [rememberThumbMaxDim]; detail keeps the default.
 */
@Composable
fun UltraAsyncImage(
    uriString: String,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    maxDim: Int = 1280,
    forceSdr: Boolean = false,
) {
    val context = LocalContext.current
    var useNative by remember(uriString, maxDim, forceSdr) { mutableStateOf(forceSdr) }
    if (!useNative) {
        AsyncImage(
            model = imageModelOf(uriString),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
            onError = { useNative = true },
        )
    } else {
        data class Loaded(val bitmap: Bitmap? = null, val finished: Boolean = false)
        val result = produceState(Loaded(), uriString, maxDim) {
            value = withContext(Dispatchers.IO) { Loaded(PreviewCache.load(context, uriString, maxDim), true) }
        }.value
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            if(result.bitmap != null) AsyncImage(model = result.bitmap, contentDescription = contentDescription, contentScale = contentScale, modifier = Modifier.fillMaxSize())
            else if(!result.finished) androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp))
            else Text("图片不可访问\n请检查文件或重新授权", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ImageFallbackPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.BrokenImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
    }
}

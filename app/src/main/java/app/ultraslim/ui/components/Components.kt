package app.ultraslim.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TextButton
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
import app.ultraslim.ui.theme.Motion
import app.ultraslim.util.formatBytes
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
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
    enabled: Boolean = true,
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
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
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
    val description: String,
    val quality: Int,
    val gainmapQuality: Int,
    val gainmapScale: Int,
    val resizePercent: Int,
    val speedLevel: Int,
) {
    QUALITY("高保真", "近乎无损质感，保留极致微观纹理", quality = 92, gainmapQuality = 70, gainmapScale = 2, resizePercent = 100, speedLevel = 1),
    BALANCED("均衡推荐", "画质无感劣化，体积精简 50%~65%", quality = 88, gainmapQuality = 60, gainmapScale = 4, resizePercent = 100, speedLevel = 1),
    EXTREME("省空间", "适度权衡，最大化节省存储空间", quality = 78, gainmapQuality = 60, gainmapScale = 4, resizePercent = 100, speedLevel = 1),
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
    enabled: Boolean = true,
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
            SectionHeader("输出格式", "推荐优先使用 AVIF、HEIC 或 JXL")
            val formats = OutputFormat.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                formats.forEachIndexed { index, format ->
                    SegmentedButton(
                        selected = defaults.format == format,
                        onClick = { callbacks.onFormatChange(format) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = formats.size),
                        icon = {},
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    ) {
                        Text(format.label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            val formatTip = when (defaults.format) {
                OutputFormat.AVIF -> "AVIF：现代 AV1 编码，体积超小；实况照片将导出为静止 HDR"
                OutputFormat.HEIC -> "HEIC：高效 HEVC 编码，相册兼容性最佳，完美保留实况动态"
                OutputFormat.JXL -> "JXL：JPEG XL 现代格式，极致纹理保真；实况照片将导出为静止 HDR"
                OutputFormat.JPEG -> "JPEG：经典兼容格式，体积缩减相对有限，完美保留实况动态"
            }
            Text(formatTip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            val matchedPreset = CompressionPreset.entries.firstOrNull { it.matches(defaults) }
            SectionHeader("画质档位", matchedPreset?.description ?: "自定义画质数值 ${defaults.quality} · Gain Map 始终无损保留")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompressionPreset.entries.forEach { preset ->
                    FilterChip(selected = matchedPreset == preset, onClick = { callbacks.onPreset(preset) }, enabled = enabled, label = { Text(preset.label) })
                }
            }
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Gain Map · 跟随原图", style = MaterialTheme.typography.titleSmall)
                    Text(if (defaults.format == OutputFormat.JPEG) "直接复用 JPEG 原始增益图；其他 HDR 输入请选择 HEIC / AVIF / JXL。"
                        else "增益图独立无损编码，不缩小、不重建；方向与主图同步。", style = MaterialTheme.typography.bodySmall)
                }
            }
            SwitchRow("仅保留更小结果", "若压缩后体积未比原图小，自动保留原图，杜绝反向变大", defaults.onlySmaller, callbacks.onOnlySmallerChange, enabled = enabled)
            var advanced by remember { mutableStateOf(false) }
            TextButton(onClick = { advanced = !advanced }, enabled = enabled) { Text(if(advanced) "收起更多选项" else "更多选项（画质数值、尺寸、速度、元数据）") }
            AnimatedVisibility(
                visible = advanced,
                enter = expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
                exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SliderOption("画质数值（进阶）", defaults.quality, 50f..100f, 0, callbacks.onQualityChange, enabled = enabled)
                    Text(
                        "数值越高画质越好、体积越大；100 是编码器档位，不代表无损。拿不准就用上面的画质档位。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SectionHeader("编码速度", "更快编码可能增加体积或影响细节，建议先试转")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "画质", 1 to "平衡", 2 to "速度").forEach { (level, label) ->
                            FilterChip(
                                selected = defaults.speedLevel == level,
                                onClick = { callbacks.onSpeedLevelChange(level) },
                                enabled = enabled,
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
                                enabled = enabled,
                                label = { Text("$percent%") },
                            )
                        }
                    }

                    SwitchRow(
                        title = "保留 EXIF",
                        hint = "保留拍摄时间、地点等元数据",
                        checked = defaults.keepExif,
                        onCheckedChange = callbacks.onKeepExifChange,
                        enabled = enabled,
                    )
                    SwitchRow("保留位置数据", "关闭后移除 EXIF GPS 信息", defaults.keepLocation, callbacks.onKeepLocationChange, enabled = enabled)
                    SwitchRow(
                        title = "允许纯 SDR 转换",
                        hint = "没有 Gain Map 的图片也进行转码",
                        checked = defaults.allowSdr,
                        onCheckedChange = callbacks.onAllowSdrChange,
                        enabled = enabled,
                    )
                }
            }
            if (showAutoSave) {
                SwitchRow(
                    title = "自动保存到相册",
                    hint = "转换完成后写入 相册/UltraSlim",
                    checked = defaults.autoSave,
                    onCheckedChange = callbacks.onAutoSaveChange,
                    enabled = enabled,
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
    enabled: Boolean = true,
) {
    var sliderValue by remember { mutableFloatStateOf(value.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var committed by remember { mutableStateOf(value) }
    // 仅跟随外部真正变化的值；拖动中预设等其他来源的更新不打断拇指位置，
    // 松手后以待提交值为准，避免状态回流前的一两帧回跳。
    if (value != committed && !dragging) {
        committed = value
        sliderValue = value.toFloat()
    }
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
            onValueChange = { dragging = true; sliderValue = it },
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = {
                dragging = false
                committed = sliderValue.roundToInt()
                onChange(committed)
            },
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
    var useNative by remember(uriString, forceSdr) { mutableStateOf(forceSdr) }
    if (!useNative) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageModelOf(uriString))
                .crossfade(Motion.Fast)
                .build(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
            onError = { useNative = true },
        )
    } else {
        val result = produceState<NativeImage?>(null, uriString, maxDim) {
            value = withContext(Dispatchers.IO) { NativeImage(PreviewCache.load(context, uriString, maxDim), true) }
        }.value
        // 上一代解码结果跨 maxDim 保留：放大触发高分辨率重解码时旧图仍在屏，
        // 不闪回转圈；只有首屏无图才显示 spinner。
        var lastBitmap by remember(uriString) { mutableStateOf<Bitmap?>(null) }
        if (result?.bitmap != null) lastBitmap = result.bitmap
        val shown = result?.bitmap ?: lastBitmap
        val state = when {
            shown != null -> NativeDecoded.Ready(shown)
            result?.finished == true -> NativeDecoded.Failed
            else -> NativeDecoded.Loading
        }
        Crossfade(targetState = state, animationSpec = tween(Motion.Fast), label = "nativeImage") { s ->
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                when (s) {
                    is NativeDecoded.Ready -> AsyncImage(model = s.bitmap, contentDescription = contentDescription, contentScale = contentScale, modifier = Modifier.fillMaxSize())
                    NativeDecoded.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
                    NativeDecoded.Failed -> Text("图片不可访问\n请检查文件或重新授权", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

private sealed interface NativeDecoded {
    data class Ready(val bitmap: Bitmap) : NativeDecoded
    data object Loading : NativeDecoded
    data object Failed : NativeDecoded
}

private data class NativeImage(val bitmap: Bitmap?, val finished: Boolean)

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

/**
 * 批次时间戳分组头部：清晰展示本次导入/转换的时间（今天 17:01 / 刚刚 / 昨天等）、张数与省下体积，并支持批次级快捷操作。
 */
@Composable
fun BatchGroupHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
    savedBytes: Long? = null,
    statusSummary: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(
                    Icons.Rounded.Schedule,
                    contentDescription = null,
                    modifier = Modifier.padding(6.dp).size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                val sub = buildString {
                    append("$count 张照片")
                    if (savedBytes != null && savedBytes > 0) {
                        append(" · 省下 ${formatBytes(savedBytes)}")
                    }
                    if (!statusSummary.isNullOrBlank()) {
                        append(" · $statusSummary")
                    }
                }
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (action != null) {
            action()
        }
    }
}


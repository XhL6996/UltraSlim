package app.ultraslim.ui.settings

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.data.ThemeMode
import app.ultraslim.model.OutputFormat
import app.ultraslim.ui.adaptive.AdaptiveWorkspace
import app.ultraslim.ui.appViewModelFactory
import app.ultraslim.ui.components.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = appViewModelFactory { SettingsViewModel(it) })
    val defaults by vm.defaults.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var confirmAutoReplace by rememberSaveable { mutableStateOf(false) }
    if (confirmAutoReplace) {
        AlertDialog(
            onDismissRequest = { confirmAutoReplace = false },
            title = { Text("开启校验后自动替换？") },
            text = { Text("将同时开启自动保存，成品保存回原相册并保留拍摄时间。仅当成品更小、完整保存且系统能解码，HDR 和实况视频均通过检查后，才将原图移入系统回收站。\n\n原图不会被直接覆盖；回收站保留期限由系统决定。Android 可能仍需确认，取消会保留原图。小窗或后台转换先保存在应用内，回到完整窗口后自动入册并继续替换；不支持校验或无法定位原图时不替换。此选项只影响之后开始的任务。") },
            confirmButton = { TextButton(onClick = { confirmAutoReplace = false; vm.setAutoReplaceOriginals(true) }) { Text("开启") } },
            dismissButton = { TextButton(onClick = { confirmAutoReplace = false }) { Text("取消") } },
        )
    }

    val versionName = remember {
        runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0).versionName
            }
        }.getOrNull().orEmpty().ifBlank { "1.0" }
    }
    val nativeVersion = remember {
        runCatching { UltraHdrBridge.nativeVersion() }.getOrDefault("不可用")
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val preferences: @Composable () -> Unit = {
        SettingsGroup("默认转码偏好", "新任务采用此方案；首页可单独微调") {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Tune, null, Modifier.size(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text("默认输出格式", style = MaterialTheme.typography.titleLarge)
                            Text("${defaults.format.label} · 品质 ${defaults.quality} · ${defaults.resizePercent}% 尺寸",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    val formats = OutputFormat.entries
                    ExpressiveChoiceRow(formats.map { it.label }, formats.indexOf(defaults.format),
                        onSelect = { vm.setFormat(formats[it]) })
                    val matchedPreset = CompressionPreset.entries.firstOrNull { it.matches(defaults) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompressionPreset.entries.forEach { preset ->
                            FilterChip(selected = matchedPreset == preset, onClick = { vm.applyPreset(preset) },
                                label = { Text(preset.label) },
                                leadingIcon = if (matchedPreset == preset) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null)
                        }
                    }
                }
            }
        }
        SettingsGroup("保存与原图", "控制成品的去向，原图始终有据可查") {
            SettingItem("仅保留更小结果", "成品没有变小时，保留原图", Icons.Rounded.Compress,
                defaults.onlySmaller, vm::setOnlySmaller, 0, 3)
            SettingItem("自动保存到相册", "保存到 UltraSlim；自动替换时存回原相册", Icons.Rounded.PhotoLibrary,
                defaults.autoSave, vm::setAutoSave, 1, 3)
            SettingItem("校验后自动替换原图",
                if (Build.VERSION.SDK_INT >= 30) "存回原相册并校验后，将原图移入系统回收站" else "需要 Android 11 或更新版本",
                Icons.Rounded.VerifiedUser, defaults.autoReplaceOriginals,
                { if (it) confirmAutoReplace = true else vm.setAutoReplaceOriginals(false) }, 2, 3,
                enabled = Build.VERSION.SDK_INT >= 30)
        }
        SettingsGroup("画面与兼容性") {
            val compatibleFormat = defaults.format == OutputFormat.AVIF || defaults.format == OutputFormat.HEIC
            if (compatibleFormat) {
                val avif = defaults.format == OutputFormat.AVIF
                SettingItem(if (avif) "AVIF 相册兼容模式" else "HEIC 相册兼容模式",
                    if (avif) "彩色增益图转换存在舍入误差；关闭后部分相册可能偏色"
                    else "保留增益图尺寸与 HDR 参数，彩色增益图色度有损；关闭后部分相册可能偏色或不显示 HDR",
                    Icons.Rounded.HdrOn,
                    if (avif) defaults.avifGainmapCompatibility else defaults.heicGainmapCompatibility,
                    { if (avif) vm.setAvifGainmapCompatibility(it) else vm.setHeicGainmapCompatibility(it) }, 0, 2)
            }
            SettingItem("允许纯 SDR 转换", "也转换没有增益图的普通照片", Icons.Rounded.Image,
                defaults.allowSdr, vm::setAllowSdr, if (compatibleFormat) 1 else 0, if (compatibleFormat) 2 else 1)
        }
        SettingsGroup("照片信息与隐私") {
            SettingItem("保留 EXIF 信息", "保留拍摄器材、曝光参数与拍摄时间", Icons.Rounded.CameraAlt,
                defaults.keepExif, vm::setKeepExif, 0, 2)
            SettingItem("保留 GPS 位置信息", "关闭后移除照片中的地理坐标", Icons.Rounded.LocationOn,
                defaults.keepLocation, vm::setKeepLocation, 1, 2)
        }
    }
    val appearanceAndStorage: @Composable () -> Unit = {
        SettingsGroup("外观与显示", "选一种看着舒服的配色") {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Palette, null, tint = MaterialTheme.colorScheme.primary)
                        Text("主题模式", style = MaterialTheme.typography.titleLarge)
                    }
                    ThemePalettePreview()
                    val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
                    ExpressiveChoiceRow(listOf("跟随系统", "浅色", "深色"), modes.indexOf(theme.themeMode),
                        onSelect = { vm.setThemeMode(modes[it]) })
                }
            }
            if (Build.VERSION.SDK_INT >= 31) SettingItem("动态取色", "让界面配色呼应你的壁纸", Icons.Rounded.ColorLens,
                theme.dynamicColor, vm::setDynamicColor, 0, 1)
        }
        SettingsGroup("存储与维护") {
            SegmentedListItem(onClick = {
                // 删目录持 diskLock 且可能上百个文件，不能阻塞主线程。
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    PreviewCache.clear(context)
                    snackbar.showSnackbar("已清理缩略图与临时缓存")
                }
            }, shapes = ListItemDefaults.segmentedShapes(0, 1),
                leadingContent = { Icon(Icons.Rounded.CleaningServices, null, tint = MaterialTheme.colorScheme.primary) },
                supportingContent = { Text("仅清理预览与临时缓存，不影响相册照片") },
                trailingContent = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) },
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) { Text("立即清理", style = MaterialTheme.typography.titleMedium) }
        }
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.tertiaryContainer) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Security, null, Modifier.size(32.dp))
                Text("照片留在你的设备", style = MaterialTheme.typography.titleLarge)
                Text("全部离线处理，默认保留原图。主动开启自动替换或手动清理时，原图才会移入系统回收站。",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        SettingsGroup("引擎与关于") {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("UltraSlim", style = MaterialTheme.typography.titleLarge)
                    Text("版本 $versionName", style = MaterialTheme.typography.bodyMedium)
                    Text(nativeVersion, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                scrollBehavior = scrollBehavior,
                colors = appTopBarColors(),
                title = { Text("设置") },
                subtitle = { Text("你的照片，你的偏好") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        AdaptiveWorkspace(
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding).padding(horizontal = 20.dp),
            expandedWidth = 600.dp,
            minPaneWidth = 280.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f),
            primary = { expanded ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    preferences()
                    if (!expanded) appearanceAndStorage()
                    Spacer(Modifier.height(24.dp))
                }
            },
            secondary = {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    appearanceAndStorage()
                    Spacer(Modifier.height(24.dp))
                }
            },
        )
    }
}

@Composable
private fun ThemePalettePreview() {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(colors.primary, colors.secondary, colors.tertiary).forEachIndexed { index, color ->
            Surface(Modifier.weight(if (index == 0) 2f else 1f).fillMaxHeight(),
                shape = if (index == 0) RoundedCornerShape(40.dp) else RoundedCornerShape(20.dp), color = color) {
                if (index == 0) Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(32.dp), tint = colors.onPrimary)
                }
            }
        }
    }
}

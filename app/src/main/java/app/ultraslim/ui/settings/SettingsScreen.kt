package app.ultraslim.ui.settings

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.data.ThemeMode
import app.ultraslim.model.OutputFormat
import app.ultraslim.ui.appViewModelFactory
import app.ultraslim.ui.components.CompressionPreset
import app.ultraslim.ui.components.InfoRow
import app.ultraslim.ui.components.PreviewCache
import app.ultraslim.ui.components.SectionHeader
import app.ultraslim.ui.components.SwitchRow
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ================= 1. 默认转码偏好 =================
            SectionHeader("默认转码偏好", "新加入处理列表的照片将默认采用此方案")
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(
                                "在这里设定的是全局默认模板；在首页中可随时针对单批照片微调。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    SectionHeader("默认输出格式")
                    val formats = OutputFormat.entries
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        formats.forEachIndexed { index, format ->
                            SegmentedButton(
                                selected = defaults.format == format,
                                onClick = { vm.setFormat(format) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = formats.size),
                                icon = {},
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            ) {
                                Text(format.label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    val matchedPreset = CompressionPreset.entries.firstOrNull { it.matches(defaults) }
                    SectionHeader("默认画质档位", matchedPreset?.description ?: "自定义品质 ${defaults.quality}")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompressionPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = matchedPreset == preset,
                                onClick = { vm.applyPreset(preset) },
                                label = { Text(preset.label) },
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    SwitchRow(
                        title = "仅保留更小结果",
                        hint = "若压缩后体积未减小，自动保留原图，杜绝反向变大",
                        checked = defaults.onlySmaller,
                        onCheckedChange = vm::setOnlySmaller,
                    )
                    SwitchRow(
                        title = "自动保存到相册",
                        hint = "转换完成后自动写入系统 相册/UltraSlim 目录",
                        checked = defaults.autoSave,
                        onCheckedChange = vm::setAutoSave,
                    )
                    if (defaults.format == OutputFormat.AVIF) {
                        SwitchRow(
                            title = "AVIF 相册兼容模式",
                            hint = "彩色增益图转换存在舍入误差；关闭后部分相册可能偏色",
                            checked = defaults.avifGainmapCompatibility,
                            onCheckedChange = vm::setAvifGainmapCompatibility,
                        )
                    }
                    if (defaults.format == OutputFormat.HEIC) {
                        SwitchRow(
                            title = "HEIC 相册兼容模式",
                            hint = "保留增益图尺寸与 HDR 参数，彩色增益图色度有损；关闭后部分相册可能偏色或不显示 HDR",
                            checked = defaults.heicGainmapCompatibility,
                            onCheckedChange = vm::setHeicGainmapCompatibility,
                        )
                    }
                    SwitchRow(
                        title = "保留 EXIF 信息",
                        hint = "保留拍摄器材、曝光参数、拍摄时间等元数据",
                        checked = defaults.keepExif,
                        onCheckedChange = vm::setKeepExif,
                    )
                    SwitchRow(
                        title = "保留 GPS 位置信息",
                        hint = "关闭后将擦除照片拍摄地理坐标，保护隐私",
                        checked = defaults.keepLocation,
                        onCheckedChange = vm::setKeepLocation,
                    )
                    SwitchRow(
                        title = "允许纯 SDR 转换",
                        hint = "对没有 Gain Map 的普通照片也执行转码",
                        checked = defaults.allowSdr,
                        onCheckedChange = vm::setAllowSdr,
                    )
                }
            }

            // ================= 2. 外观与显示 =================
            SectionHeader("外观与显示")
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SectionHeader("主题模式")
                    val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
                    val labels = listOf("跟随系统", "浅色", "深色")
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = theme.themeMode == mode,
                                onClick = { vm.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                            ) {
                                Text(labels[index])
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SwitchRow(
                            title = "动态取色 (Monet)",
                            hint = "从当前壁纸提取配色主题",
                            checked = theme.dynamicColor,
                            onCheckedChange = vm::setDynamicColor,
                        )
                    }
                }
            }

            // ================= 3. 存储与维护 =================
            SectionHeader("存储与维护")
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("预览图与临时缓存", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "清理原图解码生成的缩略缓存，不影响相册中的任何图片",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = {
                            PreviewCache.clear(context)
                            scope.launch { snackbar.showSnackbar("已清理缩略图与临时缓存") }
                        }) {
                            Icon(Icons.Rounded.CleaningServices, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("立即清理")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.Security, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            "原图保护承诺：源文件始终在设备中安全保留，绝不直接覆盖或删除原图。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ================= 4. 引擎与关于 =================
            SectionHeader("引擎与关于")
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InfoRow("应用版本", versionName)
                    InfoRow("原生引擎架构", nativeVersion)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            "全部转码与增益图处理完全在设备本地离线执行，无任何网络上传。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

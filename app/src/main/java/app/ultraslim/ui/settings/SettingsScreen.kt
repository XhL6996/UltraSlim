package app.ultraslim.ui.settings

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ultraslim.bridge.UltraHdrBridge
import app.ultraslim.data.ThemeMode
import app.ultraslim.ui.appViewModelFactory
import app.ultraslim.ui.components.ConversionOptionsPanel
import app.ultraslim.ui.components.InfoRow
import app.ultraslim.ui.components.OptionsCallbacks
import app.ultraslim.ui.components.SectionHeader
import app.ultraslim.ui.components.SwitchRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = appViewModelFactory { SettingsViewModel(it) })
    val defaults by vm.defaults.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()

    val context = LocalContext.current
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
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader("外观")
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SectionHeader("主题")
                    val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
                    val labels = listOf("跟随系统", "浅色", "深色")
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = theme.themeMode == mode,
                                onClick = { vm.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = modes.size,
                                ),
                            ) {
                                Text(labels[index])
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SwitchRow(
                            title = "动态取色",
                            hint = "从壁纸提取主题色",
                            checked = theme.dynamicColor,
                            onCheckedChange = { vm.setDynamicColor(it) },
                        )
                    }
                }
            }

            SectionHeader("默认转换选项", modifier = Modifier.padding(top = 8.dp))
            ConversionOptionsPanel(
                defaults = defaults,
                callbacks = remember(vm) {
                    OptionsCallbacks(
                        onFormatChange = vm::setFormat,
                        onQualityChange = vm::setQuality,
                        onGainmapQualityChange = vm::setGainmapQuality,
                        onGainmapScaleChange = vm::setGainmapScale,
                        onMultiChannelChange = vm::setMultiChannel,
                        onKeepExifChange = vm::setKeepExif,
                        onAllowSdrChange = vm::setAllowSdr,
                        onAutoSaveChange = vm::setAutoSave,
                        onPreset = vm::applyPreset,
                        onSpeedLevelChange = vm::setSpeedLevel,
                        onResizePercentChange = vm::setResizePercent,
                        onOnlySmallerChange = vm::setOnlySmaller,
                        onKeepLocationChange = vm::setKeepLocation,
                    )
                },
            )

            SectionHeader("关于", modifier = Modifier.padding(top = 8.dp))
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InfoRow("应用版本", versionName)
                    InfoRow("引擎版本", nativeVersion)
                    Text(
                        "基于 libultrahdr · libheif · x265 · aom · libde265 · libjpeg-turbo",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

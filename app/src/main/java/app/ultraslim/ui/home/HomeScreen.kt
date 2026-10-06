package app.ultraslim.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ultraslim.bridge.ProbeResult
import app.ultraslim.convert.QueueItem
import app.ultraslim.convert.QueueStatus
import app.ultraslim.ui.appViewModelFactory
import app.ultraslim.ui.components.BadgeChip
import app.ultraslim.ui.components.ConversionOptionsPanel
import app.ultraslim.ui.components.EmptyState
import app.ultraslim.ui.components.OptionsCallbacks
import app.ultraslim.ui.components.SectionHeader
import app.ultraslim.ui.components.StatsCard
import app.ultraslim.util.formatBytes
import app.ultraslim.util.formatSavedPercent
import app.ultraslim.ui.components.UltraAsyncImage
import app.ultraslim.ui.components.rememberThumbMaxDim

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenDetail: (Long) -> Unit,
) {
    val vm: HomeViewModel = viewModel(factory = appViewModelFactory { HomeViewModel(it) })
    val state by vm.uiState.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (!uris.isNullOrEmpty()) vm.addItems(uris)
    }

    var hasNotifPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasNotifPermission = granted
        vm.startConversion()
    }

    val mediaPermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var hasMediaPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, mediaPermission) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMediaPermission = granted
        if (granted) vm.scanAlbum()
    }
    val startScan: () -> Unit = {
        if (hasMediaPermission) {
            vm.scanAlbum()
        } else {
            mediaPermissionLauncher.launch(mediaPermission)
        }
    }

    val launchPicker: () -> Unit = {
        pickLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }
    val startConversion: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (hasNotifPermission || Build.VERSION.SDK_INT < 33) {
            vm.startConversion()
        } else {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is HomeEvent.Finished -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (event.failed > 0) {
                        val result = snackbarHostState.showSnackbar(
                            message = "完成 ${event.done} 张，${event.failed} 张失败",
                            actionLabel = "重试",
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) vm.retryFailed()
                    } else if (event.done > 0) {
                        val suffix = if (event.skipped > 0) "，跳过 ${event.skipped} 张" else ""
                        snackbarHostState.showSnackbar(
                            "完成 ${event.done} 张，共节省 ${formatBytes(event.savedBytes)}$suffix",
                        )
                    } else {
                        snackbarHostState.showSnackbar("没有图片被转换")
                    }
                }

                is HomeEvent.Info -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("UltraSlim", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = startScan, enabled = !scanning) {
                        if (scanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Rounded.ImageSearch, contentDescription = "扫描相册")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "设置")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            HomeFab(
                state = state,
                onPick = launchPicker,
                onConvert = startConversion,
                onCancel = { vm.cancelConversion() },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        if (state.queue.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatsCard(
                    totalSavedBytes = state.totalSavedBytes,
                    convertedCount = state.convertedCount,
                )
                EmptyState(
                    icon = Icons.Rounded.AddPhotoAlternate,
                    title = "把 Ultra HDR 照片压缩成 HEIC / AVIF",
                    subtitle = "体积更小，HDR 效果完整保留",
                    actionLabel = "选择图片",
                    onAction = launchPicker,
                )
                ConversionOptionsPanel(
                    defaults = state.defaults,
                    callbacks = rememberOptionsCallbacks(vm),
                )
                Spacer(Modifier.height(96.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "stats") {
                    StatsCard(
                        totalSavedBytes = state.totalSavedBytes,
                        convertedCount = state.convertedCount,
                    )
                }
                item(key = "options") {
                    ConversionOptionsPanel(
                        defaults = state.defaults,
                        callbacks = rememberOptionsCallbacks(vm),
                    )
                }
                item(key = "queue_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionHeader(
                            title = "转换队列 · ${state.queue.size}",
                            hint = "左滑可移除",
                        )
                        Spacer(Modifier.weight(1f))
                        if (state.hasFinishedItems && !state.isConverting) {
                            TextButton(onClick = { vm.clearFinished() }) {
                                Text("清除已完成")
                            }
                        }
                    }
                }
                items(state.queue, key = { it.id }) { item ->
                    QueueRow(
                        item = item,
                        onRemove = { vm.removeItem(item.id) },
                        onRetry = { vm.retryItem(item.id) },
                        onOpen = {
                            val historyId = item.historyId
                            if (item.status == QueueStatus.DONE && historyId != null) {
                                onOpenDetail(historyId)
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberOptionsCallbacks(vm: HomeViewModel): OptionsCallbacks =
    remember(vm) {
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
        )
    }

@Composable
private fun HomeFab(
    state: HomeUiState,
    onPick: () -> Unit,
    onConvert: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.queue.isEmpty()) {
            ExtendedFloatingActionButton(
                text = { Text("选择图片") },
                icon = { Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null) },
                onClick = onPick,
                expanded = true,
            )
        } else {
            SmallFloatingActionButton(
                onClick = onPick,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "添加")
            }
            ExtendedFloatingActionButton(
                text = {
                    Text(
                        when {
                            state.isConverting -> "取消"
                            else -> "开始转换 (${state.convertibleCount})"
                        },
                    )
                },
                icon = {
                    if (state.isConverting) {
                        Icon(Icons.Rounded.Close, contentDescription = null)
                    } else {
                        Icon(Icons.Rounded.Compress, contentDescription = null)
                    }
                },
                onClick = {
                    when {
                        state.isConverting -> onCancel()
                        state.convertibleCount > 0 -> onConvert()
                    }
                },
                expanded = true,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueRow(
    item: QueueItem,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.Settled) {
                false
            } else if (item.status == QueueStatus.CONVERTING) {
                false
            } else {
                onRemove()
                true
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val color by animateColorAsState(
                targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.Settled) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                label = "swipeBg",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.large)
                    .background(color)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "移除",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        QueueCard(item = item, onRetry = onRetry, onRemove = onRemove, onOpen = onOpen)
    }
}

@Composable
private fun QueueCard(
    item: QueueItem,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
) {
    Card(
        onClick = onOpen,
        enabled = item.status == QueueStatus.DONE && item.historyId != null,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UltraAsyncImage(
                uriString = item.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                maxDim = rememberThumbMaxDim(),
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (item.sizeBytes > 0) formatBytes(item.sizeBytes) else "大小未知",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.status == QueueStatus.FAILED && !item.error.isNullOrBlank()) {
                    Text(
                        item.error.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FormatBadge(item.probe, item.status)
                StatusChip(item)
            }
            when (item.status) {
                QueueStatus.CONVERTING -> {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }

                QueueStatus.FAILED -> {
                    IconButton(onClick = onRetry) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "重试",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                else -> {
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "移除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatBadge(probe: ProbeResult?, status: QueueStatus) {
    when {
        probe == null -> if (status == QueueStatus.PROBING || status == QueueStatus.PENDING) {
            BadgeChip(
                "检测中",
                MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        probe.hasGainMap && probe.container == 0 -> BadgeChip(
            "Ultra HDR",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )

        probe.hasGainMap -> BadgeChip(
            "Gainmap",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )

        else -> BadgeChip(
            "SDR",
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusChip(item: QueueItem) {
    val scheme = MaterialTheme.colorScheme
    when (item.status) {
        QueueStatus.PENDING -> BadgeChip(
            "待处理",
            scheme.surfaceContainerHighest,
            scheme.onSurfaceVariant,
        )

        QueueStatus.PROBING -> BadgeChip(
            "检测中",
            scheme.surfaceContainerHighest,
            scheme.onSurfaceVariant,
        )

        QueueStatus.READY -> BadgeChip(
            "就绪",
            scheme.secondaryContainer,
            scheme.onSecondaryContainer,
        )

        QueueStatus.CONVERTING -> BadgeChip(
            "转换中 ${item.progress}%",
            scheme.primaryContainer,
            scheme.onPrimaryContainer,
        )

        QueueStatus.DONE -> {
            val result = item.result
            val percent = if (result != null) {
                " ${formatSavedPercent(result.inputBytes, result.outputBytes)}"
            } else {
                ""
            }
            BadgeChip(
                "完成$percent",
                scheme.tertiaryContainer,
                scheme.onTertiaryContainer,
            )
        }

        QueueStatus.FAILED -> BadgeChip(
            "失败",
            scheme.errorContainer,
            scheme.onErrorContainer,
        )

        QueueStatus.SKIPPED -> BadgeChip(
            "已跳过",
            scheme.surfaceContainerHighest,
            scheme.onSurfaceVariant,
        )
    }
}

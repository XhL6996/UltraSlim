package app.ultraslim.ui.home

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ultraslim.bridge.ProbeResult
import app.ultraslim.convert.*
import app.ultraslim.data.summary
import app.ultraslim.ui.appViewModelFactory
import app.ultraslim.ui.components.*
import app.ultraslim.ui.theme.Motion
import app.ultraslim.util.*
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(onOpenSettings: () -> Unit, onOpenDetail: (Long) -> Unit) {
    val vm: HomeViewModel = viewModel(factory = appViewModelFactory { HomeViewModel(it) })
    val state by vm.uiState.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val scanProgress by vm.scanProgress.collectAsStateWithLifecycle()
    val stopping by vm.stopping.collectAsStateWithLifecycle()
    val railLayout = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width / androidx.compose.ui.platform.LocalDensity.current.density >= 840
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var editOptions by remember { mutableStateOf(false) }
    var showAlbums by remember { mutableStateOf(false) }
    var pendingAlbums by remember { mutableStateOf(false) }
    val albums by vm.albums.collectAsStateWithLifecycle()
    val loadingAlbums by vm.loadingAlbums.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addItems(it) }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startConversion() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) {
            if (pendingAlbums) { vm.loadAlbums(); showAlbums = true }
            else vm.scanAlbum()
        } else vm.permissionDenied()
    }
    val imagesPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    val scan = {
        pendingAlbums = false
        if (ContextCompat.checkSelfPermission(context, imagesPermission) == PackageManager.PERMISSION_GRANTED) vm.scanAlbum()
        else permission.launch(if (Build.VERSION.SDK_INT >= 34) arrayOf(imagesPermission, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else arrayOf(imagesPermission))
    }
    val openAlbums = {
        pendingAlbums = true
        if (ContextCompat.checkSelfPermission(context, imagesPermission) == PackageManager.PERMISSION_GRANTED) { vm.loadAlbums(); showAlbums = true }
        else permission.launch(if (Build.VERSION.SDK_INT >= 34) arrayOf(imagesPermission, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else arrayOf(imagesPermission))
    }
    val start = {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else vm.startConversion()
    }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            // 参数弹窗遮在 Snackbar 之上：弹窗开着时把通知挂起到关闭后再展示。
            if (editOptions) snapshotFlow { editOptions }.first { !it }
            when(event) {
                is HomeEvent.Info -> snackbar.showSnackbar(event.message)
                is HomeEvent.Finished -> {
                    val result = snackbar.showSnackbar("完成 ${event.done} · 失败 ${event.failed} · 跳过 ${event.skipped}；输出净变化 ${formatByteDelta(-event.savedBytes)}",
                        actionLabel = if(event.failed > 0) "重试失败项" else null, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) vm.retryFailed()
                }
            }
        }
    }
    // 转换一旦开始就收起参数弹窗并复位标记，避免运行结束后弹窗自己再次出现。
    LaunchedEffect(state.isConverting) { if (state.isConverting) editOptions = false }
    val callbacks = remember(vm) { OptionsCallbacks(vm::setFormat, vm::setQuality, vm::setGainmapQuality,
        vm::setGainmapScale, vm::setMultiChannel, vm::setKeepExif, vm::setAllowSdr, vm::setAutoSave,
        vm::applyPreset, vm::setSpeedLevel, vm::setResizePercent, vm::setOnlySmaller, vm::setKeepLocation) }
    Scaffold(
        topBar = { TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Rounded.Layers, null, Modifier.padding(8.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Column { Text("UltraSlim", style = MaterialTheme.typography.titleLarge); Text("PHOTO COMPRESSOR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } }, actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "设置") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0,0,0,0),
        bottomBar = {
            AnimatedVisibility(
                visible = state.queue.isNotEmpty(),
                enter = slideInVertically(Motion.standard()) { it } + fadeIn(Motion.standard()),
                exit = slideOutVertically(Motion.exit()) { it } + fadeOut(Motion.exit()),
            ) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 8.dp) {
                    Column(Modifier.fillMaxWidth().then(if (railLayout) Modifier.navigationBarsPadding() else Modifier).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedIconButton(onClick = pick, enabled = !state.isConverting, modifier = Modifier.size(52.dp), shape = MaterialTheme.shapes.medium) { Icon(Icons.Rounded.AddPhotoAlternate, "添加图片") }
                            OutlinedIconButton(onClick = openAlbums, enabled = !state.isConverting, modifier = Modifier.size(52.dp), shape = MaterialTheme.shapes.medium) { Icon(Icons.Rounded.PhotoLibrary, "从相册批量导入") }
                            val mode = when {
                                stopping -> HomeAction.Stopping
                                state.isConverting -> HomeAction.Converting
                                state.convertibleCount > 0 -> HomeAction.Ready
                                else -> HomeAction.Done
                            }
                            Button(onClick = { if(state.isConverting) vm.cancelConversion() else start() }, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                                shape = MaterialTheme.shapes.medium, enabled = !stopping && (state.isConverting || state.convertibleCount > 0)) {
                                AnimatedContent(
                                    targetState = mode,
                                    transitionSpec = { fadeIn(Motion.fast()) togetherWith fadeOut(Motion.exit(Motion.Fast)) },
                                    label = "mainAction",
                                ) { m ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        when (m) {
                                            HomeAction.Converting -> { Icon(Icons.Rounded.Stop, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("停止转换") }
                                            HomeAction.Stopping -> { Icon(Icons.Rounded.Stop, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("正在停止…") }
                                            HomeAction.Ready -> { Icon(Icons.Rounded.Compress, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("转换 ${state.convertibleCount} 张照片") }
                                            HomeAction.Done -> { Icon(Icons.Rounded.Compress, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("本批次已处理") }
                                        }
                                    }
                                }
                            }
                        }
                        val secondary = when {
                            !state.isConverting && state.convertibleCount > 1 -> "try"
                            stopping -> "waiting"
                            else -> "hint"
                        }
                        AnimatedContent(
                            targetState = secondary,
                            transitionSpec = { fadeIn(Motion.fast()) togetherWith fadeOut(Motion.exit(Motion.Fast)) },
                            label = "secondaryAction",
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) { s ->
                            when (s) {
                                "try" -> TextButton(onClick = vm::tryOne) { Text("先试转一张，比较效果与体积") }
                                "waiting" -> Text("等待当前编码结束", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else -> Text("原图始终保留 · 全程在设备上处理", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 840.dp
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(
                        start = 20.dp, top = 20.dp, end = 20.dp,
                        // rail 模式且队列为空时没有底部操作栏，补上导航条避让。
                        bottom = 20.dp + if (railLayout && state.queue.isEmpty()) WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        AnimatedContent(
                            targetState = state.queue.isEmpty(),
                            transitionSpec = { fadeIn(Motion.standard()) togetherWith fadeOut(Motion.exit()) },
                            label = "importHero",
                        ) { empty ->
                            ImportHero(empty, pick, openAlbums, scan, scanning)
                        }
                    }
                    item {
                        AnimatedVisibility(
                            visible = scanning,
                            enter = expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
                            exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
                                Text(scanProgress, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = vm::cancelScan) { Text("停止") }
                            }
                        }
                    }
                    item {
                        Card(onClick = { if(!state.isConverting) editOptions = true }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("输出方案", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        val matchedPreset = CompressionPreset.entries.firstOrNull { it.matches(state.defaults) }
                                        Text("${state.defaults.format.label} · " + (matchedPreset?.label ?: "画质 ${state.defaults.quality}"), style = MaterialTheme.typography.titleMedium)
                                    }
                                    Icon(Icons.Rounded.Tune, "调整本次参数", tint = MaterialTheme.colorScheme.primary)
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FeatureLabel(Icons.Rounded.Layers, "原始 Gain Map")
                                    FeatureLabel(Icons.Rounded.AspectRatio, if(state.defaults.resizePercent == 100) "原始尺寸" else "尺寸 ${state.defaults.resizePercent}%")
                                    FeatureLabel(Icons.Rounded.Verified, if(state.defaults.onlySmaller) "保证更小" else "不限制体积（可能更大）")
                                }
                                if(state.defaults.quality >= 95) Text("高画质档：压不过原图的照片会自动保留原图，不会输出更大的文件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item {
                        AnimatedVisibility(
                            visible = state.queue.isNotEmpty(),
                            enter = expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
                            exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("处理列表", style = MaterialTheme.typography.titleLarge)
                                    Text("${state.queue.size} 张 · ${formatBytes(state.queue.sumOf { it.sizeBytes })}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item {
                        AnimatedVisibility(
                            visible = (state.hasFinishedItems || state.queue.any { it.status == QueueStatus.FAILED }) && !state.isConverting,
                            enter = expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
                            exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
                        ) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if(state.hasFinishedItems) TextButton(onClick = vm::clearFinished) { Text("清除成功项") }
                                if(state.queue.any { it.status == QueueStatus.FAILED }) TextButton(onClick = vm::retryFailed) { Text("重试失败项") }
                            }
                        }
                    }
                    // 待处理（含转换中）在前，已完成（成功/跳过/失败）在后：新导入的只会
                    // 出现在"待处理"组里，一眼可辨；状态变化时行平滑跨组移动（key 不变）。
                    val pending = state.queue.filter {
                        it.status != QueueStatus.DONE && it.status != QueueStatus.SKIPPED && it.status != QueueStatus.FAILED
                    }
                    val finished = state.queue.filter {
                        it.status == QueueStatus.DONE || it.status == QueueStatus.SKIPPED || it.status == QueueStatus.FAILED
                    }
                    if (pending.isNotEmpty()) item(key = "hdr-pending") {
                        Text("待处理 · ${pending.size}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    items(pending, key = { it.id }) { item ->
                        QueueRow(item, onRemove = { vm.removeItem(item.id) }, onRetry = { vm.retryItem(item.id) }, onOpen = { item.historyId?.let(onOpenDetail) },
                            modifier = Modifier.animateItem(fadeInSpec = Motion.fast(), placementSpec = Motion.itemPlacement(), fadeOutSpec = Motion.exit(Motion.Fast)))
                    }
                    if (finished.isNotEmpty()) item(key = "hdr-finished") {
                        Text("已完成 · ${finished.size}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    items(finished, key = { it.id }) { item ->
                        QueueRow(item, onRemove = { vm.removeItem(item.id) }, onRetry = { vm.retryItem(item.id) }, onOpen = { item.historyId?.let(onOpenDetail) },
                            modifier = Modifier.animateItem(fadeInSpec = Motion.fast(), placementSpec = Motion.itemPlacement(), fadeOutSpec = Motion.exit(Motion.Fast)))
                    }
                    item {
                        AnimatedVisibility(
                            visible = state.queue.isEmpty(),
                            enter = expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
                            exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                GuideStep("01", "选择原图", "支持 JPEG / HEIC / AVIF", Modifier.weight(1f))
                                GuideStep("02", "比较结果", "先试转，再决定批量保存", Modifier.weight(1f))
                            }
                        }
                    }
                }
                // 参数列常驻（转换中仅锁定并提示），不再随转换启停整列消失。
                AnimatedVisibility(
                    visible = wide,
                    enter = expandHorizontally(Motion.emphasized()) + fadeIn(Motion.emphasized()),
                    exit = shrinkHorizontally(Motion.exit(Motion.Emphasized)) + fadeOut(Motion.exit(Motion.Emphasized)),
                ) {
                    Column(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        AnimatedVisibility(
                            visible = state.isConverting,
                            enter = expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
                            exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
                        ) {
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Rounded.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("转换中 · 参数已锁定", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Text("本次参数", style = MaterialTheme.typography.titleLarge)
                        ConversionOptionsPanel(state.defaults, callbacks, enabled = !state.isConverting)
                    }
                }
            }
        }
    }
    if(editOptions) ModalBottomSheet(onDismissRequest = { editOptions = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("调整输出方案", style = MaterialTheme.typography.headlineSmall)
            Text("仅应用于本次任务", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ConversionOptionsPanel(state.defaults, callbacks)
            Button(onClick = { editOptions = false }, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
    if(showAlbums) ModalBottomSheet(onDismissRequest = { showAlbums = false }) {
        Column(Modifier.padding(20.dp)) {
            Text("从相册批量导入", style = MaterialTheme.typography.headlineSmall)
            Text("选择一个图集，里面的照片整批加入处理列表（单次最多 1000 张）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            if(loadingAlbums) {
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            } else if(albums.isEmpty()) {
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                    Text("没有读到任何相册，请检查照片权限", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(albums, key = { it.id }) { album ->
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { showAlbums = false; vm.importAlbum(album) }.padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UltraAsyncImage(album.coverUri, null, ContentScale.Crop, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer), maxDim = 112)
                            Column(Modifier.weight(1f)) {
                                Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${album.count} 张", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private enum class HomeAction { Stopping, Converting, Ready, Done }

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
        modifier = Modifier.fillMaxWidth().clickable(enabled = item.status == QueueStatus.DONE && item.historyId != null, onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UltraAsyncImage(item.uri, null, ContentScale.Crop, Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer), maxDim = 384)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if(item.probe != null) "${item.probe.width} × ${item.probe.height}  ·  ${formatBytes(item.sizeBytes)}" else formatBytes(item.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FormatBadge(item.probe, item.status)
                }
                AnimatedContent(
                    targetState = item.status == QueueStatus.CONVERTING,
                    transitionSpec = { fadeIn(Motion.fast()) togetherWith fadeOut(Motion.exit(Motion.Fast)) },
                    label = "queueTrailing",
                ) { converting ->
                    if (converting) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "照片操作") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if(item.status == QueueStatus.SKIPPED || item.status == QueueStatus.FAILED) DropdownMenuItem(text = { Text("重新转换") }, onClick = { menu = false; onRetry() })
                                DropdownMenuItem(text = { Text("从列表移除") }, onClick = { menu = false; onRemove() })
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = item.status == QueueStatus.CONVERTING,
                enter = expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
                exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
            ) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            AnimatedVisibility(
                visible = item.status == QueueStatus.DONE || item.status == QueueStatus.SKIPPED || item.status == QueueStatus.FAILED,
                enter = expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
                exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(when(item.status) { QueueStatus.DONE -> Icons.Rounded.CheckCircle; QueueStatus.FAILED -> Icons.Rounded.ErrorOutline; else -> Icons.Rounded.Info }, null, Modifier.size(18.dp), tint = if(item.status == QueueStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(when(item.status) { QueueStatus.DONE -> item.result?.let { "${formatBytes(it.inputBytes)} → ${formatBytes(it.outputBytes)}" } ?: "转换完成"; QueueStatus.SKIPPED -> "保留原图"; else -> "转换未完成" }, style = MaterialTheme.typography.labelLarge)
                            if(!item.error.isNullOrBlank()) Text(item.error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if(item.status == QueueStatus.DONE) { StatusChip(item); Icon(Icons.Rounded.ChevronRight, "查看结果", Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GuideStep(number: String, title: String, hint: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(number, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = .55f))
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ImportHero(empty: Boolean, onPick: () -> Unit, onAlbums: () -> Unit, onScan: () -> Unit, scanning: Boolean) {
    val ink = Color(0xFF173C2F)
    val accent = Color(0xFFD9ECA3)
    if(!empty) {
        Surface(color = ink, contentColor = Color.White, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Rounded.FilterHdr, null, Modifier.size(32.dp), tint = accent)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("好照片，轻装保存。", style = MaterialTheme.typography.titleMedium)
                    Text("保留原图 · 保留原始增益图", style = MaterialTheme.typography.bodySmall, color = Color(0xFFD0DFD4))
                }
            }
        }
        return
    }
    Surface(color = ink, contentColor = Color.White, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("少一点体积，多一点空间", color = accent, style = MaterialTheme.typography.labelMedium)
                    Text(if(empty) "为照片减负。\n留下光与细节。" else "好照片，轻装保存。", style = if(empty) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall)
                }
                if(empty) Box(Modifier.size(72.dp).border(1.dp, accent.copy(alpha=.4f), RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.FilterHdr, null, Modifier.size(44.dp), tint = accent)
                }
            }
            Text("主图高效压缩 · Gain Map 跟随原图", style = MaterialTheme.typography.bodySmall, color = Color(0xFFD0DFD4))
            if(empty) {
                Button(onClick = onPick, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = ink)) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("选择照片", fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onAlbums, modifier = Modifier.align(Alignment.CenterHorizontally), colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD0DFD4))) { Text("从相册批量导入") }
                TextButton(onClick = onScan, enabled = !scanning, modifier = Modifier.align(Alignment.CenterHorizontally), colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD0DFD4))) { Text("从最近 500 张照片中查找 HDR") }
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
            "正在编码",
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

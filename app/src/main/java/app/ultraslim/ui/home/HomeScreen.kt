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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.shape.CircleShape
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
import app.ultraslim.data.ConversionDefaults
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
    val importing by vm.importing.collectAsStateWithLifecycle()
    val importProgress by vm.importProgress.collectAsStateWithLifecycle()
    val railLayout = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width / androidx.compose.ui.platform.LocalDensity.current.density >= 840
    val pendingItems = remember(state.queue) {
        state.queue.filter { it.status != QueueStatus.DONE && it.status != QueueStatus.SKIPPED && it.status != QueueStatus.FAILED }
    }
    val finishedItems = remember(state.queue) {
        state.queue.filter { it.status == QueueStatus.DONE || it.status == QueueStatus.SKIPPED || it.status == QueueStatus.FAILED }
    }
    val pendingGroups = remember(pendingItems) {
        groupByTimeSession(
            items = pendingItems,
            timestampSelector = { it.createdAt },
            batchIdSelector = { it.batchId },
        )
    }
    val finishedGroups = remember(finishedItems) {
        groupByTimeSession(
            items = finishedItems,
            timestampSelector = { it.createdAt },
            batchIdSelector = { it.batchId },
        )
    }
    var confirmClear by remember { mutableStateOf<String?>(null) }  // "pending" | "all"
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var optionsExpanded by remember { mutableStateOf(false) }
    var showAlbums by remember { mutableStateOf(false) }
    var hdrOnly by remember { mutableStateOf(false) }
    val albums by vm.albums.collectAsStateWithLifecycle()
    val loadingAlbums by vm.loadingAlbums.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addItems(it) }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startConversion() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) { vm.loadAlbums(); showAlbums = true }
        else vm.permissionDenied()
    }
    val imagesPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    val openAlbums = {
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
    // 转换一旦开始就收起参数折叠面板，避免遮挡照片列表。
    LaunchedEffect(state.isConverting) { if (state.isConverting) optionsExpanded = false }
    val callbacks = remember(vm) { OptionsCallbacks(vm::setFormat, vm::setQuality, vm::setGainmapQuality,
        vm::setGainmapScale, vm::setMultiChannel, vm::setKeepExif, vm::setAllowSdr, vm::setAutoSave,
        vm::applyPreset, vm::setSpeedLevel, vm::setResizePercent, vm::setOnlySmaller, vm::setKeepLocation) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "UltraSlim",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0,0,0,0),
        bottomBar = {
            AnimatedVisibility(
                visible = state.queue.isNotEmpty(),
                enter = slideInVertically(Motion.standard()) { it } + fadeIn(Motion.standard()),
                exit = slideOutVertically(Motion.exit()) { it } + fadeOut(Motion.exit()),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .then(if (railLayout) Modifier.navigationBarsPadding() else Modifier)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                        if (!state.isConverting && state.convertibleCount > 1) {
                            TextButton(
                                onClick = vm::tryOne,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            ) {
                                Text("先试转一张，比较效果与体积")
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
                    item(key = "import-hero") {
                        AnimatedVisibility(
                            visible = state.queue.isEmpty(),
                            enter = expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
                            exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
                        ) {
                            ImportHero(onPick = pick, onAlbums = openAlbums)
                        }
                    }
                    item {
                        AnimatedVisibility(
                            visible = scanning || importing,
                            enter = expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
                            exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                ) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text(if (scanning) scanProgress else importProgress, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    if (scanning) TextButton(onClick = vm::cancelScan) { Text("停止") }
                                }
                            }
                        }
                    }
                    if (!wide) {
                        item(key = "output-scheme-card") {
                            OutputSchemeCard(
                                defaults = state.defaults,
                                callbacks = callbacks,
                                expanded = optionsExpanded,
                                onToggleExpand = { if (!state.isConverting) optionsExpanded = !optionsExpanded },
                                enabled = !state.isConverting,
                            )
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
                            visible = state.queue.isNotEmpty(),
                            enter = expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
                            exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
                        ) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if(pendingItems.isNotEmpty()) TextButton(onClick = { if(pendingItems.size >= 20) confirmClear = "pending" else vm.clearPending() }) { Text("清除待处理") }
                                if(state.queue.any { it.status == QueueStatus.FAILED }) TextButton(onClick = vm::retryFailed) { Text("重试失败项") }
                                if(state.hasFinishedItems) TextButton(onClick = vm::clearFinished) { Text("清除成功项") }
                                TextButton(onClick = { confirmClear = "all" }) { Text("全部清除", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    // 待处理（含转换中）在前，按导入时间戳分组显示
                    if (pendingGroups.isNotEmpty()) {
                        item(key = "hdr-pending") {
                            Text("待处理 · ${pendingItems.size}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                        pendingGroups.forEach { group ->
                            if (pendingGroups.size > 1) {
                                item(key = "group-pending-${group.key}") {
                                    BatchGroupHeader(
                                        title = "${group.title} 导入",
                                        count = group.items.size,
                                        statusSummary = "待处理",
                                        action = {
                                            IconButton(
                                                onClick = { vm.removeBatch(group.items.map { it.id }) },
                                                modifier = Modifier.size(32.dp),
                                            ) {
                                                Icon(Icons.Rounded.Close, "移除此批", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    )
                                }
                            }
                            items(group.items, key = { it.id }) { item ->
                                QueueRow(
                                    item,
                                    onRemove = { vm.removeItem(item.id) },
                                    onRetry = { vm.retryItem(item.id) },
                                    onOpen = { item.historyId?.let(onOpenDetail) },
                                    modifier = Modifier.animateItem(fadeInSpec = Motion.fast(), placementSpec = Motion.itemPlacement(), fadeOutSpec = Motion.exit(Motion.Fast))
                                )
                            }
                        }
                    }

                    // 已完成（成功/跳过/失败）在后，按导入与转换时间戳清晰分组
                    if (finishedGroups.isNotEmpty()) {
                        item(key = "hdr-finished") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("已完成 · ${finishedItems.size}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (state.hasFinishedItems) {
                                    TextButton(onClick = vm::clearFinished) {
                                        Text("清除成功项")
                                    }
                                }
                            }
                        }
                        finishedGroups.forEach { group ->
                            val groupSaved = group.items.sumOf { (it.result?.inputBytes ?: it.sizeBytes) - (it.result?.outputBytes ?: it.sizeBytes) }
                            val failedCount = group.items.count { it.status == QueueStatus.FAILED }
                            val skippedCount = group.items.count { it.status == QueueStatus.SKIPPED }
                            val doneCount = group.items.count { it.status == QueueStatus.DONE }
                            item(key = "group-finished-${group.key}") {
                                BatchGroupHeader(
                                    title = group.title,
                                    count = group.items.size,
                                    savedBytes = groupSaved,
                                    statusSummary = when {
                                        failedCount > 0 && doneCount > 0 -> "$doneCount 成功 · $failedCount 失败"
                                        failedCount > 0 -> "$failedCount 项失败"
                                        skippedCount > 0 && doneCount > 0 -> "$doneCount 成功 · $skippedCount 保留原图"
                                        skippedCount > 0 -> "$skippedCount 项保留原图"
                                        else -> "全部成功"
                                    },
                                    action = {
                                        IconButton(
                                            onClick = { vm.removeBatch(group.items.map { it.id }) },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(Icons.Rounded.Close, "清除此批", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                )
                            }
                            items(group.items, key = { it.id }) { item ->
                                QueueRow(
                                    item,
                                    onRemove = { vm.removeItem(item.id) },
                                    onRetry = { vm.retryItem(item.id) },
                                    onOpen = { item.historyId?.let(onOpenDetail) },
                                    modifier = Modifier.animateItem(fadeInSpec = Motion.fast(), placementSpec = Motion.itemPlacement(), fadeOutSpec = Motion.exit(Motion.Fast))
                                )
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
    if(showAlbums) ModalBottomSheet(onDismissRequest = { showAlbums = false }) {
        Column(Modifier.padding(20.dp)) {
            Text("从相册导入", style = MaterialTheme.typography.headlineSmall)
            Text("选择一个图集整批加入处理列表（单次最多 1000 张）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("只导入 HDR 照片", style = MaterialTheme.typography.titleSmall)
                    Text("逐张检测增益图，较慢；只把 HDR 照片加入列表", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = hdrOnly, onCheckedChange = { hdrOnly = it })
            }
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { showAlbums = false; vm.scanLibraryForHdr() }.padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Rounded.TravelExplore, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text("扫描整个图库", style = MaterialTheme.typography.titleSmall)
                    Text("不限数量，逐张查找 HDR 照片", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f), modifier = Modifier.padding(vertical = 8.dp))
            if(loadingAlbums) {
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            } else if(albums.isEmpty()) {
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                    Text("没有读到任何相册，请检查照片权限", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(albums, key = { it.id }) { album ->
                        val action = { showAlbums = false; if (hdrOnly) vm.scanAlbumForHdr(album) else vm.importAlbum(album) }
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = action).padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UltraAsyncImage(album.coverUri, null, ContentScale.Crop, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer), maxDim = 112)
                            Column(Modifier.weight(1f)) {
                                Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (hdrOnly) "${album.count} 张 · 只导入 HDR" else "${album.count} 张", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    confirmClear?.let { what ->
        val count = if (what == "pending") pendingItems.size else state.queue.size
        AlertDialog(
            onDismissRequest = { confirmClear = null },
            title = { Text(if (what == "pending") "清除 $count 张待处理照片？" else "清空处理列表（$count 张）？") },
            text = { Text("列表记录会被移除；已保存到相册的成品不受影响，原图始终保留。") },
            confirmButton = { TextButton(onClick = { confirmClear = null; if (what == "pending") vm.clearPending() else vm.clearAll() }) { Text("清除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmClear = null }) { Text("取消") } },
        )
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ImportHero(
    onPick: () -> Unit,
    onAlbums: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.FilterHdr,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Ultra HDR 照片瘦身",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "无损保留增益图 · 显著压缩体积",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onPick,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("选择照片")
                }
                FilledTonalButton(
                    onClick = onAlbums,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("相册导入")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FeatureLabel(Icons.Rounded.AutoAwesome, "ISO 21496-1")
                FeatureLabel(Icons.Rounded.Speed, "NEON 加速")
                FeatureLabel(Icons.Rounded.Security, "离线处理")
            }
        }
    }
}

@Composable
private fun OutputSchemeCard(
    defaults: ConversionDefaults,
    callbacks: OptionsCallbacks,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.standard(),
        label = "optionsChevron",
    )
    val activePreset = remember(defaults) {
        CompressionPreset.entries.firstOrNull { it.matches(defaults) }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled, onClick = onToggleExpand)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "输出方案",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!enabled) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "转换中已锁定",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BadgeChip(
                            text = defaults.format.label,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        BadgeChip(
                            text = activePreset?.label ?: "质量 ${defaults.quality}",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (defaults.resizePercent != 100) {
                            BadgeChip(
                                text = "${defaults.resizePercent}%",
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onToggleExpand,
                    enabled = enabled,
                    modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
                ) {
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起配置" else "展开配置",
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
                exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
            ) {
                Column(Modifier.padding(top = 12.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    ConversionOptionsPanel(
                        defaults = defaults,
                        callbacks = callbacks,
                        enabled = enabled,
                    )
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
            "Gainmap · ${probe.containerLabel}",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )

        else -> BadgeChip(
            "SDR · ${probe.containerLabel}",
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

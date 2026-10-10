package app.ultraslim.ui.home

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.ui.draw.rotate
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
import kotlinx.coroutines.launch

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
    var lastTrashCount by remember { mutableIntStateOf(0) }
    val trashableOriginals by vm.trashableOriginalUris.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val trashBatchLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            vm.onBatchOriginalsTrashed(lastTrashCount)
        }
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when(event) {
                is HomeEvent.Info -> snackbar.showSnackbar(event.message)
                is HomeEvent.Finished -> {
                    if (event.trialHistoryId != null) {
                        val result = snackbar.showSnackbar(
                            message = "试转完成！节省 ${formatBytes(event.savedBytes)} · 点击立即对比画质",
                            actionLabel = "对比画质",
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            onOpenDetail(event.trialHistoryId)
                        }
                    } else {
                        val trashable = if (MediaStoreTrashHelper.isTrashSupported) vm.refreshAndGetTrashable() else emptyList()
                        val actionLabel = when {
                            event.failed > 0 -> "重试失败项"
                            trashable.isNotEmpty() -> "移入回收站 (${trashable.size})"
                            else -> null
                        }
                        val result = snackbar.showSnackbar(
                            message = "完成 ${event.done} · 失败 ${event.failed} · 跳过 ${event.skipped}；输出净变化 ${formatByteDelta(-event.savedBytes)}",
                            actionLabel = actionLabel,
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            if (event.failed > 0) {
                                vm.retryFailed()
                            } else if (trashable.isNotEmpty()) {
                                lastTrashCount = trashable.size
                                val sender = vm.requestTrashOriginals()
                                if (sender != null) {
                                    trashBatchLauncher.launch(IntentSenderRequest.Builder(sender).build())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(state.isConverting) { if (state.isConverting) optionsExpanded = false }
    val callbacks = remember(vm) {
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
            onAvifGainmapCompatibilityChange = vm::setAvifGainmapCompatibility,
            onHeicGainmapCompatibilityChange = vm::setHeicGainmapCompatibility,
            onSaveAsDefault = vm::saveCurrentAsDefault,
            onResetToDefault = vm::resetToDefault,
        )
    }
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
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (railLayout) Modifier.navigationBarsPadding() else Modifier)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedIconButton(
                            onClick = pick,
                            enabled = !state.isConverting,
                            modifier = Modifier.size(48.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Rounded.AddPhotoAlternate, "添加图片", modifier = Modifier.size(22.dp))
                        }
                        OutlinedIconButton(
                            onClick = openAlbums,
                            enabled = !state.isConverting,
                            modifier = Modifier.size(48.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Rounded.PhotoLibrary, "从相册批量导入", modifier = Modifier.size(22.dp))
                        }
                        val mode = when {
                            stopping -> HomeAction.Stopping
                            state.isConverting -> HomeAction.Converting
                            state.convertibleCount > 0 -> HomeAction.Ready
                            else -> HomeAction.Done
                        }
                        Button(
                            onClick = { if (state.isConverting) vm.cancelConversion() else start() },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !stopping && (state.isConverting || state.convertibleCount > 0),
                        ) {
                            AnimatedContent(
                                targetState = mode,
                                transitionSpec = { fadeIn(Motion.fast()) togetherWith fadeOut(Motion.exit(Motion.Fast)) },
                                label = "mainAction",
                            ) { m ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    when (m) {
                                        HomeAction.Converting -> {
                                            Icon(Icons.Rounded.Stop, null, Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("停止转换")
                                        }
                                        HomeAction.Stopping -> {
                                            Icon(Icons.Rounded.Stop, null, Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("正在停止…")
                                        }
                                        HomeAction.Ready -> {
                                            Icon(Icons.Rounded.Compress, null, Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("转换 ${state.convertibleCount} 张照片")
                                        }
                                        HomeAction.Done -> {
                                            Icon(Icons.Rounded.Compress, null, Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("本批次已处理")
                                        }
                                    }
                                }
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
                            ImportHero(
                                defaults = state.defaults,
                                callbacks = callbacks,
                                onPick = pick,
                                onAlbums = openAlbums,
                            )
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
                        item(key = "scheme-card") {
                            AnimatedVisibility(
                                visible = state.queue.isNotEmpty(),
                                enter = expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
                                exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
                            ) {
                                OutputSchemeCard(
                                    defaults = state.defaults,
                                    callbacks = callbacks,
                                    expanded = optionsExpanded,
                                    onToggleExpand = { if (!state.isConverting) optionsExpanded = !optionsExpanded },
                                    enabled = !state.isConverting,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                    item {
                        AnimatedVisibility(
                            visible = state.queue.isNotEmpty(),
                            enter = expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
                            exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("处理列表", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("${state.queue.size} 张 · ${formatBytes(state.totalQueueBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (!state.isConverting && state.convertibleCount > 1) {
                                        OutlinedButton(
                                            onClick = vm::tryOne,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.height(32.dp),
                                        ) {
                                            Text("先试转 1 张", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                    val hasFailed = state.hasFailed
                                    if (hasFailed) {
                                        AssistChip(
                                            onClick = vm::retryFailed,
                                            label = { Text("重试失败") },
                                            leadingIcon = { Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp)) },
                                        )
                                    }
                                    var queueMenu by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { queueMenu = true }) {
                                            Icon(Icons.Rounded.MoreVert, "列表操作")
                                        }
                                        DropdownMenu(expanded = queueMenu, onDismissRequest = { queueMenu = false }) {
                                            if (pendingItems.isNotEmpty()) {
                                                DropdownMenuItem(
                                                    text = { Text("清除待处理照片") },
                                                    leadingIcon = { Icon(Icons.Rounded.ClearAll, null) },
                                                    onClick = {
                                                        queueMenu = false
                                                        if (pendingItems.size >= 20) confirmClear = "pending" else vm.clearPending()
                                                    }
                                                )
                                            }
                                            if (state.hasFinishedItems) {
                                                DropdownMenuItem(
                                                    text = { Text("清除已完成记录") },
                                                    leadingIcon = { Icon(Icons.Rounded.DoneAll, null) },
                                                    onClick = {
                                                        queueMenu = false
                                                        vm.clearFinished()
                                                    }
                                                )
                                            }
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                text = { Text("清空整个处理列表", color = MaterialTheme.colorScheme.error) },
                                                leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                                                onClick = {
                                                    queueMenu = false
                                                    confirmClear = "all"
                                                }
                                            )
                                        }
                                    }
                                }
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
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (MediaStoreTrashHelper.isTrashSupported) {
                                        if (trashableOriginals.isNotEmpty()) {
                                            TextButton(onClick = {
                                                val count = trashableOriginals.size
                                                lastTrashCount = count
                                                scope.launch {
                                                    val sender = vm.requestTrashOriginals()
                                                    if (sender != null) trashBatchLauncher.launch(IntentSenderRequest.Builder(sender).build())
                                                }
                                            }) {
                                                Icon(Icons.Rounded.DeleteSweep, null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("移入回收站 (${trashableOriginals.size})")
                                            }
                                        }
                                    }
                                    if (state.hasFinishedItems) {
                                        TextButton(onClick = vm::clearFinished) {
                                            Text("清除成功项")
                                        }
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
    if (showAlbums) {
        ModalBottomSheet(onDismissRequest = { showAlbums = false }) {
            var albumTab by remember { mutableIntStateOf(0) }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("导入照片", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))

                PrimaryTabRow(
                    selectedTabIndex = albumTab,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                ) {
                    Tab(
                        selected = albumTab == 0,
                        onClick = { albumTab = 0 },
                        text = { Text("按相册导入") },
                        icon = { Icon(Icons.Rounded.PhotoLibrary, null) },
                    )
                    Tab(
                        selected = albumTab == 1,
                        onClick = { albumTab = 1 },
                        text = { Text("智能探查 HDR") },
                        icon = { Icon(Icons.Rounded.TravelExplore, null) },
                    )
                }

                Spacer(Modifier.height(16.dp))

                if (albumTab == 0) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("仅导入 HDR 照片", style = MaterialTheme.typography.titleSmall)
                            Text("逐张分析增益图；仅将 HDR 照片加入任务队列", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = hdrOnly, onCheckedChange = { hdrOnly = it })
                    }
                    if (loadingAlbums) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(32.dp))
                        }
                    } else if (albums.isEmpty()) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                            Text("未读取到任何相册，请检查照片访问权限", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(albums, key = { it.id }) { album ->
                                val action = { showAlbums = false; if (hdrOnly) vm.scanAlbumForHdr(album) else vm.importAlbum(album) }
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    onClick = action,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        UltraAsyncImage(album.coverUri, null, ContentScale.Crop, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer), maxDim = 112)
                                        Column(Modifier.weight(1f)) {
                                            Text(album.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(if (hdrOnly) "${album.count} 张 · 只导入 HDR" else "${album.count} 张照片", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                                    Icon(Icons.Rounded.TravelExplore, null, Modifier.padding(10.dp).size(24.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                }
                                Column {
                                    Text("全图库深度扫描", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("自动甄别相册中的 Ultra HDR / Gain Map 照片", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(
                                "系统将逐张探查照片元数据与增益图流，不限数量。探查出的 HDR 照片将自动归集到待处理列表中，普通 SDR 照片会被自动跳过。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { showAlbums = false; vm.scanLibraryForHdr() },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("开始全库智能探查")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
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
                    Text(if(item.probe != null) "${item.probe.width} × ${item.probe.height}  ·  ${formatBytes(item.sizeBytes)}" else formatBytes(item.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FormatBadge(item.probe, item.status)
                        if (item.displayName.startsWith("MVIMG_", ignoreCase = true) || item.displayName.contains("MotionPhoto", ignoreCase = true)) {
                            BadgeChip("实况", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        val p = item.probe
                        if (p != null && p.width > 0 && p.height > 0) {
                            val mp = (p.width.toLong() * p.height) / 1_000_000
                            val aspect = p.width.toFloat() / p.height.toFloat()
                            if (mp >= 20) {
                                BadgeChip("${mp}MP 超清", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                            } else if (aspect >= 2.2f || aspect <= 0.45f) {
                                BadgeChip("全景", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
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
    defaults: ConversionDefaults,
    callbacks: OptionsCallbacks,
    onPick: () -> Unit,
    onAlbums: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var heroOptionsExpanded by remember { mutableStateOf(false) }
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
                        "保留 HDR 效果 · 精简照片体积",
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

            OutputSchemeCard(
                defaults = defaults,
                callbacks = callbacks,
                expanded = heroOptionsExpanded,
                onToggleExpand = { heroOptionsExpanded = !heroOptionsExpanded },
                enabled = true,
            )

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
        animationSpec = Motion.accordion(),
        label = "optionsChevron",
    )
    val activePreset = remember(defaults) {
        CompressionPreset.entries.firstOrNull { it.matches(defaults) }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled, onClick = onToggleExpand)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Icon(
                        Icons.Rounded.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "输出方案",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    BadgeChip(
                        text = defaults.format.label,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    BadgeChip(
                        text = activePreset?.label ?: "品质 ${defaults.quality}",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (defaults.resizePercent != 100) {
                        BadgeChip(
                            text = "${defaults.resizePercent}%",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (expanded) "收起" else "微调",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起配置" else "展开配置",
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(chevronRotation),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = Motion.accordion(),
                    expandFrom = Alignment.Top,
                    clip = true,
                ) + fadeIn(
                    animationSpec = Motion.standard(),
                ),
                exit = shrinkVertically(
                    animationSpec = Motion.accordionExit(),
                    shrinkTowards = Alignment.Top,
                    clip = true,
                ) + fadeOut(
                    animationSpec = Motion.exit(200),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
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

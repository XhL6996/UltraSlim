package app.ultraslim.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import app.ultraslim.ui.theme.Motion
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ultraslim.convert.OutputStore
import app.ultraslim.data.HistoryEntity
import app.ultraslim.ui.appViewModelFactory
import app.ultraslim.ui.components.*
import app.ultraslim.util.*
import java.time.LocalDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(onOpenDetail: (Long) -> Unit) {
    val vm: HistoryViewModel = viewModel(factory = appViewModelFactory { HistoryViewModel(it) })
    val state by vm.uiState.collectAsStateWithLifecycle()
    val deleting by vm.deleteCandidates.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var filter by remember { mutableStateOf("全部") }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmClear by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var collapsedDates by remember { mutableStateOf(emptySet<LocalDate>()) }
    fun finishSelection() { selecting = false; selected = emptySet() }
    BackHandler(selecting && !state.busy) { finishSelection() }
    LaunchedEffect(vm) { vm.events.collect { snackbar.showSnackbar(it) } }
    val visible = remember(state.items, filter) { state.items.filter { it.matchesHistoryFilter(filter) } }
    val selectedItems = visible.filter { it.id in selected }
    val pendingCount = selectedItems.count { it.saveState != "SAVED" }
    LaunchedEffect(visible) { selected = selected.intersect(visible.map { it.id }.toSet()) }
    val groups = remember(visible) { visible.groupBy { historyDate(it.createdAt) } }
    val railMode = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width /
        androidx.compose.ui.platform.LocalDensity.current.density >= 840

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text(if (selecting) "已选择 ${selectedItems.size} 张" else "成品库", style = MaterialTheme.typography.titleLarge)
                    Text(if (selecting) "点击图片勾选，可批量分享或删除" else "每一次减负，都看得见",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, actions = {
                TextButton(onClick = { if (selecting) finishSelection() else selecting = true }, enabled = !state.busy) {
                    Text(if (selecting) "完成" else "选择")
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }, enabled = !state.busy) { Icon(Icons.Rounded.MoreVert, "历史操作") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (selecting) DropdownMenuItem(text = { Text("删除所选记录") }, enabled = selectedItems.isNotEmpty(),
                            onClick = { menu = false; vm.requestDelete(selectedItems.map { it.id }.toSet()) })
                        else DropdownMenuItem(text = { Text("清空历史记录") }, enabled = state.items.isNotEmpty(),
                            onClick = { menu = false; confirmClear = true })
                    }
                }
            })
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AnimatedVisibility(visible = selecting, modifier = Modifier.fillMaxWidth()) {
                Surface(modifier = Modifier.fillMaxWidth().testTag("history-selection-bar"), tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth()
                        .then(if (railMode) Modifier.navigationBarsPadding() else Modifier)
                        .padding(horizontal = 16.dp, vertical = 8.dp)) {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (pendingCount > 0 && android.os.Build.VERSION.SDK_INT >= 29) {
                                Button(onClick = { vm.saveSelected(selectedItems.map { it.id }.toSet()) }, enabled = !state.busy) {
                                    Text(if (state.busy) "处理中…" else "保存 $pendingCount 张")
                                }
                            }
                            OutlinedButton(onClick = {
                                if (!OutputStore.share(context, selectedItems)) scope.launch { snackbar.showSnackbar("分享失败，文件不可访问") }
                            }, enabled = !state.busy && selectedItems.isNotEmpty()) { Text("分享") }
                            OutlinedButton(onClick = { vm.requestDelete(selectedItems.map { it.id }.toSet()) },
                                enabled = !state.busy && selectedItems.isNotEmpty()) { Text("删除") }
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("history-list"),
            contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp,
                bottom = 20.dp + if (railMode && !selecting) WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { StatsCard(state.totalSavedBytes, state.convertedCount) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("全部", "待保存", "已保存", "保存失败").forEach { label ->
                        FilterChip(selected = filter == label, enabled = !state.busy,
                            onClick = { filter = label; selected = emptySet() }, label = { Text(label) })
                    }
                }
                if (selecting) {
                    val allSelected = visible.isNotEmpty() && visible.all { it.id in selected }
                    TextButton(enabled = !state.busy && visible.isNotEmpty(), onClick = {
                        selected = if (allSelected) emptySet() else visible.map { it.id }.toSet()
                    }) { Text(if (allSelected) "取消全选" else "全选当前页") }
                } else if (visible.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "点击日期可折叠 · 右滑可删除当天",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (groups.size > 1) {
                            val allCollapsed = collapsedDates.containsAll(groups.keys)
                            TextButton(
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                onClick = {
                                    collapsedDates = if (allCollapsed) emptySet<LocalDate>() else groups.keys
                                }
                            ) {
                                Text(if (allCollapsed) "展开全部" else "折叠全部", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            if (visible.isEmpty()) item { EmptyState(Icons.Rounded.History, "暂无此类记录", "完成的成品和保存状态会显示在这里") }
            groups.forEach { (date, records) ->
                val isCollapsed = date in collapsedDates
                item(key = "date-group-$date") {
                    val latestDelete by rememberUpdatedState({ vm.requestDeleteDate(date, filter) })
                    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = {
                        if (it == SwipeToDismissBoxValue.StartToEnd) latestDelete()
                        false // Confirmation precedes deletion; cancellation leaves the header in place.
                    }, positionalThreshold = { it * 0.35f })
                    Column(modifier = Modifier.fillMaxWidth().animateItem(placementSpec = Motion.itemPlacement())) {
                        SwipeToDismissBox(state = swipe, modifier = Modifier.testTag("history-date-$date"), enableDismissFromStartToEnd = !selecting && !state.busy,
                            enableDismissFromEndToStart = false,
                            backgroundContent = {
                                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.DeleteOutline, null)
                                        Spacer(Modifier.width(8.dp)); Text("删除当天记录")
                                    }
                                }
                            }) {
                            Surface(
                                color = if (isCollapsed) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.background,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = Motion.accordion()),
                            ) {
                                BatchGroupHeader(
                                    title = historyDateTitle(date),
                                    count = records.size,
                                    savedBytes = records.sumOf { it.inputBytes - it.outputBytes },
                                    collapsible = true,
                                    collapsed = isCollapsed,
                                    onToggleCollapse = {
                                        collapsedDates = if (isCollapsed) collapsedDates - date else collapsedDates + date
                                    },
                                    action = {
                                        if (selecting) {
                                            val ids = records.map { it.id }.toSet()
                                            val allSelected = selected.containsAll(ids)
                                            TextButton(enabled = !state.busy, onClick = {
                                                selected = if (allSelected) selected - ids else selected + ids
                                                if (isCollapsed) collapsedDates = collapsedDates - date
                                            }) {
                                                Text(if (allSelected) "取消当天" else "选择当天")
                                            }
                                        } else IconButton(onClick = { latestDelete() }, enabled = !state.busy) {
                                            Icon(Icons.Rounded.DeleteOutline, "删除${historyDateTitle(date)}的记录")
                                        }
                                    }
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = !isCollapsed,
                            enter = expandVertically(
                                animationSpec = Motion.accordion(),
                                expandFrom = Alignment.Top,
                                clip = true,
                            ) + fadeIn(
                                animationSpec = Motion.standard(),
                            ) + slideInVertically(
                                animationSpec = Motion.accordion(),
                                initialOffsetY = { -it / 6 },
                            ),
                            exit = shrinkVertically(
                                animationSpec = Motion.accordionExit(),
                                shrinkTowards = Alignment.Top,
                                clip = true,
                            ) + fadeOut(
                                animationSpec = Motion.exit(240),
                            ) + slideOutVertically(
                                animationSpec = Motion.accordionExit(),
                                targetOffsetY = { -it / 6 },
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                records.forEach { entity ->
                                    val checked = entity.id in selected
                                    HistoryRow(
                                        entity = entity,
                                        selecting = selecting,
                                        checked = checked,
                                        enabled = !state.busy,
                                        onClick = {
                                            if (selecting) selected = if (checked) selected - entity.id else selected + entity.id
                                            else onOpenDetail(entity.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (state.items.size < state.convertedCount) item {
                TextButton(onClick = vm::loadMore, modifier = Modifier.fillMaxWidth()) { Text("加载更多记录") }
            }
        }
    }
    if (deleting.isNotEmpty()) AlertDialog(onDismissRequest = vm::cancelDelete,
        title = { Text("删除 ${deleting.size} 条记录？") },
        text = { Text("删除这些转换记录及未保存的应用内成品。已保存到相册的图片和原图不受影响。") },
        confirmButton = { TextButton(onClick = { selected = selected - deleting.map { it.id }.toSet(); vm.deleteConfirmed() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = vm::cancelDelete) { Text("取消") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空历史？") },
        text = { Text("删除全部转换记录和未保存的应用内成品。已保存到相册的图片不受影响。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; finishSelection(); vm.clearAll() }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}

@Composable
internal fun HistoryRow(entity: HistoryEntity, selecting: Boolean, checked: Boolean, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().testTag("history-row-${entity.id}"),
        border = if (checked) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
            UltraAsyncImage(entity.outputUri, null, ContentScale.Crop, Modifier.padding(start = if (selecting) 8.dp else 0.dp)
                .size(72.dp).clip(RoundedCornerShape(14.dp)), 128)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(entity.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text("${entity.format} · ${entity.width} × ${entity.height}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text("${formatBytes(entity.inputBytes)} → ${formatBytes(entity.outputBytes)}", style = MaterialTheme.typography.titleSmall)
                Text("体积 ${formatSavedPercent(entity.inputBytes, entity.outputBytes)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(when (entity.saveState) { "SAVED" -> "已保存到相册"; "FAILED" -> "保存失败 · 可重试 / 导出"; else -> "待保存 · 可直接分享" },
                    style = MaterialTheme.typography.labelSmall, color = if (entity.saveState == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        }
    }
}

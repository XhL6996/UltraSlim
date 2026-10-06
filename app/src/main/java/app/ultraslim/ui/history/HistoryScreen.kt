package app.ultraslim.ui.history

import androidx.compose.foundation.background
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ultraslim.convert.OutputStore
import app.ultraslim.ui.appViewModelFactory
import app.ultraslim.ui.components.*
import app.ultraslim.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(onOpenDetail: (Long) -> Unit) {
    val vm: HistoryViewModel = viewModel(factory = appViewModelFactory { HistoryViewModel(it) })
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var filter by remember { mutableStateOf("全部") }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirm by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.events.collect { snackbar.showSnackbar(it) } }
    val visible = state.items.filter { when(filter) { "待保存" -> it.saveState != "SAVED"; "已保存" -> it.saveState == "SAVED"; "保存失败" -> it.saveState == "FAILED"; else -> true } }
    Scaffold(topBar = { TopAppBar(title = { Column { Text("成品库", style = MaterialTheme.typography.titleLarge); Text("每一次减负，都看得见", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, actions = {
            TextButton(onClick = { selecting = !selecting; if(!selecting) selected=emptySet() }) { Text(if(selecting) "完成" else "选择") }
            var menu by remember { mutableStateOf(false) }
            Box { IconButton(onClick = {menu=true}) { Icon(Icons.Rounded.MoreVert,"历史操作") }
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) { DropdownMenuItem(text={Text("清空历史记录")},onClick={menu=false;confirm=true},enabled=!state.busy) }
            }
        }) },
        contentWindowInsets = WindowInsets(0,0,0,0), snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if(selected.isNotEmpty()) Surface(tonalElevation = 3.dp) {
            FlowRow(Modifier.navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveSelected(selected) }, enabled = !state.busy && android.os.Build.VERSION.SDK_INT >= 29) { Text(if(state.busy) "保存中…" else "保存 ${selected.size} 张") }
                OutlinedButton(onClick = { if(!OutputStore.share(context, state.items.filter { it.id in selected })) android.widget.Toast.makeText(context,"分享失败，文件不可访问",android.widget.Toast.LENGTH_LONG).show() }, enabled = !state.busy) { Text("分享") }
                TextButton(onClick = {selected=emptySet()}) { Text("取消选择") }
            }
        } }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { StatsCard(state.totalSavedBytes,state.convertedCount) }
            item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("全部","待保存","已保存","保存失败").forEach { label -> FilterChip(selected=filter==label,onClick={filter=label},label={Text(label)}) }
                if(selecting) TextButton(onClick={selected=visible.map {it.id}.toSet()}) {Text("全选当前页")}
            } }
            if(visible.isEmpty()) item { EmptyState(Icons.Rounded.History,"暂无此类记录","完成的成品和保存状态会显示在这里") }
            visible.groupBy {it.batchId.ifBlank {"legacy"}}.forEach { (batch, records) ->
                item(key="batch-$batch") { Text(if(batch=="legacy") "以前的转换" else "批次 · ${formatRelativeTime(records.first().createdAt)} · ${records.size} 张",style=MaterialTheme.typography.titleSmall) }
                items(records,key={it.id}) { entity ->
                    Card(onClick={if(selecting) selected=if(entity.id in selected) selected-entity.id else selected+entity.id else onOpenDetail(entity.id)},modifier=Modifier.fillMaxWidth(), colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            if(selecting) Checkbox(checked=entity.id in selected,onCheckedChange={selected=if(it) selected+entity.id else selected-entity.id})
                            UltraAsyncImage(entity.outputUri,null,ContentScale.Crop,Modifier.size(80.dp).clip(RoundedCornerShape(14.dp)),384)
                            Column(Modifier.weight(1f).padding(start=12.dp)) {
                                Text(entity.displayName,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                                Text("${entity.format} · ${entity.width} × ${entity.height}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(6.dp))
                                Text("${formatBytes(entity.inputBytes)} → ${formatBytes(entity.outputBytes)}",style=MaterialTheme.typography.titleSmall)
                                Text("体积 ${formatSavedPercent(entity.inputBytes,entity.outputBytes)}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                                Text(when(entity.saveState){"SAVED"->"已保存到相册";"FAILED"->"保存失败 · 点击重试 / 导出";else->"待保存 · 可直接分享"},style=MaterialTheme.typography.labelSmall,color=if(entity.saveState=="FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            if(state.items.size<state.convertedCount) item { TextButton(onClick=vm::loadMore,modifier=Modifier.fillMaxWidth()){Text("加载更多记录")} }
        }
    }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("清空历史？")},text={Text("删除全部转换记录和未保存的应用内成品。已保存到相册的图片不受影响。")},confirmButton={TextButton(onClick={confirm=false;selected=emptySet();vm.clearAll()}){Text("清空")}},dismissButton={TextButton(onClick={confirm=false}){Text("取消")}})
}

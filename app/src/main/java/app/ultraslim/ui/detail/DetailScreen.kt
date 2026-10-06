package app.ultraslim.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Slider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import app.ultraslim.convert.OutputStore
import app.ultraslim.model.OutputFormat
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ultraslim.data.HistoryEntity
import app.ultraslim.ui.appViewModelFactory
import app.ultraslim.ui.components.BadgeChip
import app.ultraslim.ui.components.InfoRow
import app.ultraslim.ui.components.SectionHeader
import app.ultraslim.ui.components.UltraAsyncImage
import app.ultraslim.util.formatBoost
import app.ultraslim.util.formatBytes
import app.ultraslim.util.formatDuration
import app.ultraslim.util.formatResolution
import app.ultraslim.util.formatSavedPercent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(historyId: Long, onBack: () -> Unit) {
    val vm: DetailViewModel = viewModel(factory = appViewModelFactory { DetailViewModel(it, historyId) })
    val state by vm.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::relinkSource) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(OutputFormat.parse(state.entity?.format).mimeType)) { it?.let(vm::exportTo) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is DetailEvent.Message -> snackbarHostState.showSnackbar(event.text)
                DetailEvent.Deleted -> onBack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("转换结果") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val entity = state.entity
        if (entity == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                if(state.loading) CircularProgressIndicator() else Text(
                    "记录不存在或已删除",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            DetailContent(
                entity = entity,
                saving = state.saving,
                onSaveToAlbum = { vm.saveToAlbum() },
                onRelink = { sourcePicker.launch(arrayOf("image/*")) },
                onExport = { export.launch(state.entity?.displayName ?: "image.heic") },
                onDelete = { showDeleteConfirm = true },
                onBack = onBack,
                context = context,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除这条记录？") },
            text = { Text("删除记录及尚未保存的应用内成品；已保存到相册的图片不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        vm.deleteRecord()
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun DetailContent(
    entity: HistoryEntity,
    saving: Boolean,
    onSaveToAlbum: () -> Unit,
    onExport: () -> Unit,
    onRelink: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    context: Context,
    modifier: Modifier = Modifier,
) {
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler { progress ->
        try {
            progress.collect { backEvent -> backProgress = backEvent.progress }
            backProgress = 0f
            onBack()
        } catch (e: CancellationException) {
            backProgress = 0f
        }
    }

    val saved = entity.outputUri.startsWith("content://")
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if(entity.outputBytes < entity.inputBytes) "为这张照片省下" else "本次转换体积增加", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(formatBytes(kotlin.math.abs(entity.inputBytes - entity.outputBytes)), style = MaterialTheme.typography.headlineLarge)
                    BadgeChip(formatSavedPercent(entity.inputBytes, entity.outputBytes), MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primary)
                }
                Text("${formatBytes(entity.inputBytes)} → ${formatBytes(entity.outputBytes)} · ${entity.format}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        SectionHeader("看看细节", "拖动分界线对比，双指放大查看")
        Text("此页为 SDR 预览；HDR 亮度请在兼容相册中查看。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HeroCompareCard(
            entity = entity,
            scale = 1f - 0.08f * backProgress,
            corner = lerp(28.dp, 48.dp, backProgress),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!saved && android.os.Build.VERSION.SDK_INT >= 29) {
                FilledTonalButton(
                    onClick = onSaveToAlbum,
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (saving) "保存中…" else "保存到相册")
                }
            }
            OutlinedButton(
                onClick = { if(!OutputStore.share(context, listOf(entity))) android.widget.Toast.makeText(context, "分享失败：文件不可访问", android.widget.Toast.LENGTH_LONG).show() },
                enabled = !saving,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("分享")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { viewInAlbum(context, entity) }, enabled = saved, modifier = Modifier.weight(1f)) { Text("在相册中查看") }
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { menu = true }, enabled = !saving) { Text("更多操作") }
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("导出到文件") }, onClick = {menu=false;onExport()})
                    androidx.compose.material3.DropdownMenuItem(text = { Text("重新关联原图") }, onClick = {menu=false;onRelink()})
                    androidx.compose.material3.DropdownMenuItem(text = { Text("删除记录") }, onClick = {menu=false;onDelete()})
                }
            }
        }
        if (!saved) {
            Text(
                if(entity.saveState == "FAILED") "保存到相册失败，成品已在应用内保留，可重试、导出或分享。" else "成品已在应用内保留，可直接分享或导出。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(entity.displayName, style = MaterialTheme.typography.titleSmall)
        Text(entity.settingsSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MetadataCard(entity)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HeroCompareCard(entity: HistoryEntity, scale: Float, corner: Dp) {
    var fraction by remember { mutableFloatStateOf(0.5f) }
    var viewSize by remember { mutableStateOf(IntSize(1, 1)) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    fun maxPan(scaleNow: Float): Offset = Offset(
        (scaleNow - 1f) * viewSize.width / 2f,
        (scaleNow - 1f) * viewSize.height / 2f,
    )

    val previewHeight = with(density) { (LocalWindowInfo.current.containerSize.height.toDp() * .4f).coerceIn(220.dp, 420.dp) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        val newScale = (zoomScale * zoom).coerceIn(1f, 4f)
        zoomScale = newScale
        val max = maxPan(newScale)
        panOffset = if(newScale <= 1f) Offset.Zero else Offset(
            (panOffset.x + pan.x).coerceIn(-max.x, max.x),
            (panOffset.y + pan.y).coerceIn(-max.y, max.y),
        )
    }
    Card(
        shape = RoundedCornerShape(corner),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(previewHeight)
                .clip(RoundedCornerShape(corner))
                .onSizeChanged {
                    viewSize = IntSize(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1))
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            val target = if (zoomScale > 1f) 1f else 2.5f
                            val startScale = zoomScale
                            val startOffset = panOffset
                            scope.launch {
                                animate(0f, 1f, animationSpec = tween(220)) { t, _ ->
                                    zoomScale = startScale + (target - startScale) * t
                                    panOffset = if (target == 1f) startOffset * (1f - t) else startOffset
                                }
                                if (target == 1f) panOffset = Offset.Zero
                            }
                        },
                    )
                }
                .transformable(state = transform, canPan = { zoomScale > 1f })
                .graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                    translationX = panOffset.x
                    translationY = panOffset.y
                },
        ) {
            // 压缩后（右侧，底层）
            UltraAsyncImage(
                uriString = entity.outputUri,
                contentDescription = "压缩后 SDR",
                contentScale = ContentScale.Fit,
                forceSdr = true,
                maxDim = if(zoomScale > 1f) 4096 else 1600,
                modifier = Modifier.fillMaxSize(),
            )

            // 原图（左侧，按分割线裁剪）。输出分辨率更小时，原图预览也按输出尺寸
            // 解码，保证两侧锐度一致、对比公平。
            val outputLong = maxOf(entity.width, entity.height)
            UltraAsyncImage(
                uriString = entity.inputUri,
                contentDescription = "原图 SDR",
                contentScale = ContentScale.Fit,
                forceSdr = true,
                maxDim = if(zoomScale > 1f) 4096 else if(outputLong in 1 until 1600) outputLong else 1600,
                modifier = Modifier.fillMaxSize().drawWithContent { clipRect(right = size.width * fraction) { this@drawWithContent.drawContent() } },
            )

            // 分割线
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { androidx.compose.ui.unit.IntOffset((viewSize.width * fraction - with(density) { 1.dp.toPx() }).toInt(), 0) }
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.9f)),
            )

            // 拖拽手柄
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { androidx.compose.ui.unit.IntOffset((viewSize.width * fraction - with(density) { 18.dp.toPx() }).toInt(), 0) }
                    .size(36.dp)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, drag ->
                            change.consume()
                            fraction = (fraction + drag / viewSize.width).coerceIn(0f, 1f)
                        }
                    },
            ) {
                Icon(
                    Icons.Rounded.DragIndicator,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }

            CompareLabel("原图", Modifier.align(Alignment.TopStart).padding(12.dp))
            CompareLabel("压缩后", Modifier.align(Alignment.TopEnd).padding(12.dp))
        }
        Slider(modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = "原图与成品比较比例" }, value = fraction, onValueChange = { fraction = it }, valueRange = 0f..1f)
        Text("左右拖动滑杆对比 · 双指缩放", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CompareLabel(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = Color.Black.copy(alpha = 0.45f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun MetadataCard(entity: HistoryEntity) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader("图片信息")
            InfoRow("格式", "${entity.inputFormat} → ${entity.format}")
            InfoRow("分辨率", formatResolution(entity.width, entity.height))
            InfoRow(
                "Gain Map 尺寸",
                if (entity.hadGainMap) formatResolution(entity.gainmapWidth, entity.gainmapHeight) else "无",
            )
            InfoRow(
                "最大亮度增益",
                if (entity.hadGainMap) formatBoost(entity.maxContentBoost) else "无",
            )
            InfoRow("原大小", formatBytes(entity.inputBytes))
            InfoRow("新大小", formatBytes(entity.outputBytes))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "体积变化",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val grew = entity.outputBytes > entity.inputBytes
                BadgeChip(
                    text = formatSavedPercent(entity.inputBytes, entity.outputBytes),
                    containerColor = if (grew) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
                    contentColor = if (grew) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    },
                )
            }
            InfoRow("耗时", formatDuration(entity.durationMs))
            InfoRow("输出 EXIF", if(entity.hasExif) "已保留" else "无 / 旧记录未校验")
        }
    }
}

private fun shareImage(context: Context, entity: HistoryEntity) {
    runCatching {
        val uri = Uri.parse(entity.outputUri)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = OutputFormat.parse(entity.format).mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享图片"))
    }
}

private fun viewInAlbum(context: Context, entity: HistoryEntity) {
    runCatching {
        val uri = Uri.parse(entity.outputUri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                uri,
                OutputFormat.parse(entity.format).mimeType,
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure { android.widget.Toast.makeText(context, "无法打开文件，请导出或选择其他查看应用", android.widget.Toast.LENGTH_LONG).show() }
}

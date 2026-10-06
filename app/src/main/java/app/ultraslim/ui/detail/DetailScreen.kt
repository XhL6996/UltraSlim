package app.ultraslim.ui.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
                title = { Text("详情") },
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
                Text(
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
            text = { Text("只会删除转换记录，相册中的图片不受影响。") },
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroCompareCard(
            entity = entity,
            scale = 1f - 0.08f * backProgress,
            corner = lerp(28.dp, 48.dp, backProgress),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!saved) {
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
                onClick = { shareImage(context, entity) },
                enabled = saved,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("分享")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { viewInAlbum(context, entity) },
                enabled = saved,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("在相册中查看")
            }
            TextButton(
                onClick = onDelete,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("删除记录")
            }
        }
        if (!saved) {
            Text(
                "此文件尚未保存到相册，保存后才能分享或在相册中查看。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
    val ratio = remember(entity.width, entity.height) {
        if (entity.width > 0 && entity.height > 0) {
            entity.width.toFloat() / entity.height.toFloat()
        } else {
            4f / 3f
        }
    }

    fun maxPan(scaleNow: Float): Offset = Offset(
        (scaleNow - 1f) * viewSize.width / 2f,
        (scaleNow - 1f) * viewSize.height / 2f,
    )

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
                .aspectRatio(ratio.coerceIn(0.5f, 1.9f))
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
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f || zoomScale > 1f) {
                            // 缩放 / 平移模式
                            val newScale = (zoomScale * zoom).coerceIn(1f, 4f)
                            zoomScale = newScale
                            val max = maxPan(newScale)
                            panOffset = if (newScale <= 1f) {
                                Offset.Zero
                            } else {
                                Offset(
                                    (panOffset.x + pan.x).coerceIn(-max.x, max.x),
                                    (panOffset.y + pan.y).coerceIn(-max.y, max.y),
                                )
                            }
                        } else {
                            // 1x 时整图拖动 = 移动分割线
                            fraction = (fraction + pan.x / viewSize.width).coerceIn(0.02f, 0.98f)
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                    translationX = panOffset.x
                    translationY = panOffset.y
                },
        ) {
            val fullWidth = maxWidth

            // 压缩后（右侧，底层）
            UltraAsyncImage(
                uriString = entity.outputUri,
                contentDescription = "压缩后",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // 原图（左侧，按分割线裁剪）
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(fullWidth * fraction)
                    .clipToBounds(),
            ) {
                UltraAsyncImage(
                    uriString = entity.inputUri,
                    contentDescription = "原图",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(fullWidth).fillMaxHeight(),
                )
            }

            // 分割线
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = fullWidth * fraction - 1.dp, y = 0.dp)
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
                    .offset(x = fullWidth * fraction - 18.dp, y = 0.dp)
                    .size(36.dp),
            ) {
                Icon(
                    Icons.Rounded.DragIndicator,
                    contentDescription = "拖动对比",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }

            CompareLabel("原图", Modifier.align(Alignment.TopStart).padding(12.dp))
            CompareLabel("压缩后", Modifier.align(Alignment.TopEnd).padding(12.dp))
        }
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
                "Gainmap 尺寸",
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
                    "压缩率",
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
        }
    }
}

private fun shareImage(context: Context, entity: HistoryEntity) {
    runCatching {
        val uri = Uri.parse(entity.outputUri)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (entity.format.equals("AVIF", ignoreCase = true)) "image/avif" else "image/heic"
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
                if (entity.format.equals("AVIF", ignoreCase = true)) "image/avif" else "image/heic",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}

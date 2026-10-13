package app.ultraslim.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Commit only on finger-up past the distance threshold; cancellation never performs an action. */
@Composable
internal fun CommitSwipeRow(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    toRight: Boolean = false,
    onCommit: () -> Unit,
    background: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    var width by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    // 显隐判断走 derivedStateOf：拖动每帧改 offset 只在 0↔非 0 切换时重组，
    // content() 的位移由 Modifier.offset 的 placement lambda 承担，不参与重组。
    val backgroundVisible by remember { derivedStateOf { offset != 0f } }
    val latestCommit by rememberUpdatedState(onCommit)
    val latestEnabled by rememberUpdatedState(enabled)
    val scope = rememberCoroutineScope()
    var resetJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun reset() {
        resetJob?.cancel()
        resetJob = scope.launch { Animatable(offset).animateTo(0f) { offset = value } }
    }
    LaunchedEffect(enabled) { if (!enabled) { resetJob?.cancel(); offset = 0f } }
    Box(modifier.onSizeChanged { width = it.width }.pointerInput(enabled, toRight) {
        if (!enabled) return@pointerInput
        detectHorizontalDragGestures(
            onDragStart = { resetJob?.cancel(); offset = 0f },
            onHorizontalDrag = { change, amount ->
                change.consume()
                offset = if (toRight) (offset + amount).coerceIn(0f, width.toFloat())
                    else (offset + amount).coerceIn(-width.toFloat(), 0f)
            },
            onDragCancel = { reset() },
            onDragEnd = {
                val commit = width > 0 && abs(offset) >= maxOf(width * 0.45f, 96.dp.toPx()) && latestEnabled
                // Reset before dispatch so undo/confirmation reuses a settled row.
                offset = 0f
                if (commit) latestCommit()
            },
        )
    }) {
        if (backgroundVisible) Box(Modifier.matchParentSize()) { background() }
        Box(Modifier.offset { IntOffset(offset.roundToInt(), 0) }) { content() }
    }
}

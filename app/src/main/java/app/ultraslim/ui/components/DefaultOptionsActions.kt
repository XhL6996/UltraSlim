package app.ultraslim.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/** Give each action the full available width before deciding whether it fits on the current line. */
@Composable
internal fun DefaultOptionsActions(
    onSaveAsDefault: (() -> Unit)?,
    onResetToDefault: (() -> Unit)?,
    enabled: Boolean,
) {
    val labelSize = rememberTextMeasurer().measure("保存为全局默认", MaterialTheme.typography.labelLarge).size
    val labelWidth = with(LocalDensity.current) { labelSize.width.toDp() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Decorative icon yields first when large text needs the button's width.
        val showIcon = maxWidth >= labelWidth + 32.dp + 18.dp + 8.dp
        Column(Modifier.fillMaxWidth().testTag("default-options-actions"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("当前仅对本次任务生效", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                onResetToDefault?.let { reset ->
                    TextButton(onClick = reset, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("重置为默认")
                    }
                }
                onSaveAsDefault?.let { save ->
                    OutlinedButton(onClick = save, enabled = enabled, shapes = ButtonDefaults.shapes(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        if (showIcon) {
                            Icon(Icons.Rounded.BookmarkBorder, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("保存为全局默认", modifier = Modifier.weight(1f, fill = false))
                    }
                }
            }
        }
    }
}

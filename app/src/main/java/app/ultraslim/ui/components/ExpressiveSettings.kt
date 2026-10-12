package app.ultraslim.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp

/** A single accessible switch target, using the official morphing segmented-list container. */
@Composable
fun SettingItem(
    title: String, hint: String, icon: ImageVector, checked: Boolean,
    onCheckedChange: (Boolean) -> Unit, index: Int, count: Int, enabled: Boolean = true,
) {
    SegmentedListItem(
        onClick = { onCheckedChange(!checked) },
        shapes = ListItemDefaults.segmentedShapes(index, count),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        supportingContent = { Text(hint, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Switch(checked, onCheckedChange = null, enabled = enabled,
                thumbContent = if (checked) ({ Icon(Icons.Rounded.Check, null, Modifier.size(SwitchDefaults.IconSize)) }) else null)
        },
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    ) { Text(title, style = MaterialTheme.typography.titleMedium) }
}

@Composable
fun SettingsGroup(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(start = 4.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
    }
}

/** A restrained common chrome colour prevents a scrolled app bar splitting the status bar at a rail. */
@Composable
fun appTopBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surface,
    scrolledContainerColor = MaterialTheme.colorScheme.surface,
)

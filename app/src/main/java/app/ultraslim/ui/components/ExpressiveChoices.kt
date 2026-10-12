package app.ultraslim.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Connected controls: shape and colour both communicate the current choice. */
@Composable
fun ExpressiveChoiceRow(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    if (labels.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + 2.dp) / (64.dp * LocalDensity.current.fontScale + 2.dp))
            .toInt().coerceIn(1, labels.size)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.chunked(columns).forEachIndexed { row, group ->
                // Row distributes pixel rounding; fixed cell widths in a FlowRow can wrap the
                // final item at fractional densities even when all buttons should fit.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    group.forEachIndexed { column, label ->
                        val index = row * columns + column
                        ToggleButton(
                            checked = selectedIndex == index,
                            onCheckedChange = { onSelect(index) },
                            enabled = enabled,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                                role = Role.RadioButton
                                selected = selectedIndex == index
                            },
                            shapes = when {
                                group.size == 1 -> ToggleButtonDefaults.shapesFor(ToggleButtonDefaults.size)
                                column == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                column == group.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp),
                        ) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
                    }
                    repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

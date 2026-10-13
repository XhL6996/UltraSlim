package app.ultraslim.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.ultraslim.ui.theme.Motion

/** Empty workspace uses direct source cards; a populated queue uses compact split actions. */
@Composable
internal fun ImportActions(
    onPick: () -> Unit,
    onAlbums: () -> Unit,
    onFolder: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    enabled: Boolean = true,
) {
    if (prominent) {
        ImportSourceCards(onPick, onAlbums, onFolder, modifier, enabled)
        return
    }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, Motion.fast(), label = "importMenuArrow")
    val height = 48.dp
    val colors = ButtonDefaults.filledTonalButtonColors()
    Box(modifier.testTag("import-actions")) {
        SplitButtonLayout(
            modifier = Modifier,
            leadingButton = {
                SplitButtonDefaults.LeadingButton(
                    onClick = onPick,
                    enabled = enabled,
                    colors = colors,
                    modifier = Modifier.heightIn(min = height),
                    shapes = SplitButtonDefaults.leadingButtonShapesFor(height),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("添加照片")
                }
            },
            trailingButton = {
                SplitButtonDefaults.TrailingButton(
                    checked = expanded,
                    onCheckedChange = { expanded = it },
                    enabled = enabled,
                    colors = colors,
                    modifier = Modifier.width(48.dp).heightIn(min = height),
                    shapes = SplitButtonDefaults.trailingButtonShapesFor(height),
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "更多导入方式", Modifier.size(24.dp).rotate(rotation))
                }
            },
        )
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("相册导入") },
                leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) },
                onClick = { expanded = false; onAlbums() },
            )
            DropdownMenuItem(
                text = { Text("文件夹导入") },
                leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) },
                onClick = { expanded = false; onFolder() },
            )
        }
    }
}

/** Direct sources form one group; text scaling can turn the paired cards into full-width rows. */
@Composable
private fun ImportSourceCards(
    onPick: () -> Unit,
    onAlbums: () -> Unit,
    onFolder: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth().testTag("import-actions")) {
        val paired = maxWidth >= 280.dp * fontScale.coerceAtLeast(1f)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(
                onClick = onPick,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 8.dp, bottomEnd = 8.dp),
                colors = CardDefaults.cardColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(32.dp))
                        Surface(shape = CircleShape, color = colors.onPrimary.copy(alpha = 0.12f)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null,
                                Modifier.padding(8.dp).size(20.dp), tint = colors.onPrimary)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("选择照片", modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge)
                        Text("自由选择一张或多张", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (paired) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImportSourceCard("相册导入", "按相册批量添加", Icons.Rounded.PhotoLibrary, onAlbums,
                        colors.secondaryContainer, colors.onSecondaryContainer,
                        RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 28.dp, bottomEnd = 8.dp),
                        Modifier.weight(1f).fillMaxHeight(), enabled)
                    ImportSourceCard("文件夹导入", "可包含子文件夹", Icons.Rounded.FolderOpen, onFolder,
                        colors.tertiaryContainer, colors.onTertiaryContainer,
                        RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 8.dp, bottomEnd = 28.dp),
                        Modifier.weight(1f).fillMaxHeight(), enabled)
                }
            } else {
                ImportSourceCard("相册导入", "按相册批量添加", Icons.Rounded.PhotoLibrary, onAlbums,
                    colors.secondaryContainer, colors.onSecondaryContainer,
                    RoundedCornerShape(8.dp), Modifier.fillMaxWidth(), enabled)
                ImportSourceCard("文件夹导入", "可包含子文件夹", Icons.Rounded.FolderOpen, onFolder,
                    colors.tertiaryContainer, colors.onTertiaryContainer,
                    RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 28.dp, bottomEnd = 28.dp),
                    Modifier.fillMaxWidth(), enabled)
            }
        }
    }
}

@Composable
private fun ImportSourceCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    shape: Shape,
    modifier: Modifier,
    enabled: Boolean,
) {
    Card(onClick = onClick, enabled = enabled, modifier = modifier, shape = shape,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

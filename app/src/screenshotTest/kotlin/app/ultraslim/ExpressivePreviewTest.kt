package app.ultraslim

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ultraslim.data.ConversionDefaults
import app.ultraslim.data.ThemeMode
import app.ultraslim.ui.adaptive.AdaptiveWorkspace
import app.ultraslim.ui.components.ConversionOptionsPanel
import app.ultraslim.ui.components.OptionsCallbacks
import app.ultraslim.ui.theme.AppTheme
import com.android.tools.screenshot.PreviewTest

@Preview(name = "Phone", widthDp = 400, heightDp = 840)
@Preview(name = "Fold", widthDp = 840, heightDp = 900)
@Preview(name = "Tablet", widthDp = 1280, heightDp = 900)
@Preview(name = "Large text", widthDp = 360, heightDp = 800, fontScale = 2f)
annotation class FormFactors

// Production controls and geometry, without Room, native codecs or a device dependency.
@PreviewTest
@FormFactors
@Composable
fun ConversionWorkspacePreview() = WorkspacePreview(ThemeMode.LIGHT)

@PreviewTest
@Preview(name = "Dark tablet", widthDp = 1280, heightDp = 900)
@Composable
fun DarkWorkspacePreview() = WorkspacePreview(ThemeMode.DARK)

@Composable
private fun WorkspacePreview(theme: ThemeMode) {
    AppTheme(themeMode = theme) {
        Surface {
            AdaptiveWorkspace(Modifier.padding(20.dp), primary = { expanded ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("让照片轻一点", style = MaterialTheme.typography.headlineLarge)
                    Text("保留 HDR 效果 · 精简照片体积")
                    if (!expanded) OptionsPreview()
                }
            }, secondary = { Column(Modifier.verticalScroll(rememberScrollState())) { OptionsPreview() } })
        }
    }
}

@Composable
private fun OptionsPreview() {
    ConversionOptionsPanel(ConversionDefaults(), OptionsCallbacks({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}))
}

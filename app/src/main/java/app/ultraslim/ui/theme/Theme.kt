package app.ultraslim.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultraslim.data.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF176B52), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEEDC), onPrimaryContainer = Color(0xFF123C2C),
    secondary = Color(0xFF556453), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7EDD9), onSecondaryContainer = Color(0xFF2A3821),
    tertiary = Color(0xFF736035), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF5E8C6), onTertiaryContainer = Color(0xFF493812),
    background = Color(0xFFF6F7F2), onBackground = Color(0xFF1D2923),
    surface = Color(0xFFF6F7F2), onSurface = Color(0xFF1D2923),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEEF1E9), surfaceContainerHigh = Color(0xFFE8ECE3),
    surfaceContainerHighest = Color(0xFFE2E7DD), surfaceVariant = Color(0xFFE2E7DD),
    onSurfaceVariant = Color(0xFF606B61), outline = Color(0xFF7E8A7E), outlineVariant = Color(0xFFD8DED3),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFBBDD9A), onPrimary = Color(0xFF233B17),
    primaryContainer = Color(0xFF264534), onPrimaryContainer = Color(0xFFD9ECCF),
    secondary = Color(0xFFBECDB4), onSecondary = Color(0xFF293526),
    secondaryContainer = Color(0xFF364333), onSecondaryContainer = Color(0xFFDFEAD6),
    tertiary = Color(0xFFE5CC96), tertiaryContainer = Color(0xFF51442B), onTertiaryContainer = Color(0xFFF5E8C6),
    background = Color(0xFF121915), onBackground = Color(0xFFE4EADF),
    surface = Color(0xFF121915), onSurface = Color(0xFFE4EADF),
    surfaceContainerLowest = Color(0xFF0C120E), surfaceContainerLow = Color(0xFF1C241E),
    surfaceContainer = Color(0xFF222C24), surfaceContainerHigh = Color(0xFF2A342C),
    surfaceContainerHighest = Color(0xFF323E34), surfaceVariant = Color(0xFF323E34),
    onSurfaceVariant = Color(0xFFBBC7B7), outlineVariant = Color(0xFF3E4A40),
)
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
)
private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)
@Composable
fun AppTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = when(themeMode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val colors = if(dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if(dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if(dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, shapes = AppShapes, typography = AppTypography, content = content)
}

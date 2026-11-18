package app.spendr

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val SpendRed = Color(0xFFFF5C7A)
val IncomeGreen = Color(0xFF14B88F)
val WarnAmber = Color(0xFFFFA928)

val HeroBrush = Brush.linearGradient(listOf(Color(0xFF5B3DF5), Color(0xFF9B5CF6), Color(0xFFEC6AA0)))
val MintBrush = Brush.linearGradient(listOf(Color(0xFF0FA47F), Color(0xFF1FC8A0), Color(0xFF6FD9E8)))

/** Distinct, friendly colours for categories / avatars. */
val Palette = listOf(
    Color(0xFF7C5CFF), Color(0xFFFF7A59), Color(0xFF1FB9A8), Color(0xFFFFB020), Color(0xFFEC6AA0),
    Color(0xFF3D8BFF), Color(0xFF8BC34A), Color(0xFFAB47BC), Color(0xFF26C6DA), Color(0xFFFF5C7A),
    Color(0xFF5C6BC0), Color(0xFF43A047), Color(0xFF90A4AE),
)

fun colorFor(key: String): Color {
    val i = CATEGORIES.keys.indexOf(key)
    return Palette[Math.floorMod(if (i >= 0) i else key.lowercase().hashCode(), Palette.size)]
}

private val Light = lightColorScheme(
    primary = Color(0xFF5B3DF5), onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E0FF), onPrimaryContainer = Color(0xFF1C0B6B),
    secondary = IncomeGreen, secondaryContainer = Color(0xFFD5F5EC), onSecondaryContainer = Color(0xFF00382A),
    background = Color(0xFFF6F5FB), surface = Color(0xFFF6F5FB),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFF0EEF8), surfaceContainerHighest = Color(0xFFE8E5F3),
    surfaceVariant = Color(0xFFECE9F6), onSurfaceVariant = Color(0xFF6B6880), outlineVariant = Color(0xFFE2DFEE),
    error = SpendRed,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB4A5FF), onPrimary = Color(0xFF25127A),
    primaryContainer = Color(0xFF3B2A9E), onPrimaryContainer = Color(0xFFE6E0FF),
    secondary = Color(0xFF4FE0B8), secondaryContainer = Color(0xFF0B4D3D), onSecondaryContainer = Color(0xFFC9F7EA),
    background = Color(0xFF0F0E17), surface = Color(0xFF0F0E17),
    surfaceContainerLowest = Color(0xFF0B0A12), surfaceContainerLow = Color(0xFF16151F),
    surfaceContainer = Color(0xFF1A1925), surfaceContainerHigh = Color(0xFF22212F), surfaceContainerHighest = Color(0xFF2B2A3A),
    surfaceVariant = Color(0xFF26253A), onSurfaceVariant = Color(0xFFA8A5BC), outlineVariant = Color(0xFF2F2E42),
    error = SpendRed,
)

@Composable
fun SpendrTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = if (isSystemInDarkTheme()) Dark else Light,
    shapes = Shapes(
        small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
    ),
    content = content,
)

package org.echoscript.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Night = darkColorScheme(
    primary = Color(0xFF65D8E6), onPrimary = Color(0xFF08272D),
    primaryContainer = Color(0xFF223141), onPrimaryContainer = Color(0xFFE0E8EF),
    secondary = Color(0xFFB0C2D2), onSecondary = Color(0xFF182431),
    secondaryContainer = Color(0xFF223141), onSecondaryContainer = Color(0xFFE0E8EF),
    background = Color(0xFF0B1219), onBackground = Color(0xFFE0E8EF),
    surface = Color(0xFF0B1219), onSurface = Color(0xFFE0E8EF),
    surfaceVariant = Color(0xFF223141), onSurfaceVariant = Color(0xFFB0C2D2),
    surfaceContainer = Color(0xFF182431), surfaceContainerLow = Color(0xFF182431),
    surfaceContainerHigh = Color(0xFF223141), surfaceContainerHighest = Color(0xFF293C4D),
    outline = Color(0xFFB0C2D2), outlineVariant = Color(0xFF344757),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF5A2425),
)
private val Day = lightColorScheme(
    primary = Color(0xFF006A76), onPrimary = Color.White,
    primaryContainer = Color(0xFFB5EFF6), onPrimaryContainer = Color(0xFF08272D),
    secondary = Color(0xFF006A76), background = Color(0xFFF3F7FB),
    surface = Color(0xFFF8FAFD), surfaceVariant = Color(0xFFE1EAF1),
    onSurface = Color(0xFF15212C), onSurfaceVariant = Color(0xFF445566),
    surfaceContainer = Color(0xFFE8F0F6), surfaceContainerLow = Color(0xFFEDF4F9),
    outlineVariant = Color(0xFFC2CEDA),
)

@Composable
internal fun EchoTheme(appearance: String, content: @Composable () -> Unit) {
    val dark = appearance == "Dark" || (appearance == "System" && isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = if (dark) Night else Day,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

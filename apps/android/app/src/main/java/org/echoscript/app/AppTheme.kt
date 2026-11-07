package org.echoscript.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Night = darkColorScheme(
    primary = Color(0xFF72DCEC), onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF123D48), onPrimaryContainer = Color(0xFFA7EDFA),
    secondary = Color(0xFFC8BBFF), onSecondary = Color(0xFF30235A),
    secondaryContainer = Color(0xFF342B50), onSecondaryContainer = Color(0xFFE9DEFF),
    background = Color(0xFF0A1017), onBackground = Color(0xFFE1EAF3),
    surface = Color(0xFF101923), onSurface = Color(0xFFE1EAF3),
    surfaceVariant = Color(0xFF202D3B), onSurfaceVariant = Color(0xFFB8C8D9),
    surfaceContainer = Color(0xFF16222F), surfaceContainerLow = Color(0xFF111C27),
    surfaceContainerHigh = Color(0xFF1D2B39), surfaceContainerHighest = Color(0xFF253545),
    outline = Color(0xFF7E90A3), outlineVariant = Color(0xFF344658),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF5A2425),
)
private val Day = lightColorScheme(
    primary = Color(0xFF006876), onPrimary = Color.White,
    primaryContainer = Color(0xFFB5EFF6), onPrimaryContainer = Color(0xFF00363F),
    secondary = Color(0xFF62528A), background = Color(0xFFF3F7FB),
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

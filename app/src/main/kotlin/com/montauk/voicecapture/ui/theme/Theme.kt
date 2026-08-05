package com.montauk.voicecapture.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Dark theme is the default: this app is used one-handed, glanced at while
// walking or driving, often in low light. Large, high-contrast type over a
// near-black background reads fastest in those conditions.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFEF6C4D),
    onPrimary = Color(0xFF1B1B1F),
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF19191C),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF232327),
    onSurfaceVariant = Color(0xFFB8B8BD),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFC1502F),
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 18.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun VoiceCaptureTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}

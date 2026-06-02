package com.fytradio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Default accent (the blue primary) and the swatches offered in the settings picker. */
const val DefaultAccentArgb: Int = 0xFF7AB7FF.toInt()
val AccentSwatches: List<Int> = listOf(
    0xFF7AB7FF, // blue (default)
    0xFF4DD0C4, // teal
    0xFF9CD67E, // green
    0xFFFFD54F, // amber
    0xFFFFB779, // orange
    0xFFFF8A80, // red
    0xFFF48FB1, // pink
    0xFFC9A7FF, // purple
).map { it.toInt() }

// Tuned for a 768x1024 portrait car screen. Mirrors FytBt's palette so the two apps look
// like siblings on the head unit launcher. `primary` is overridden by the user's accent.
private val CarDark = darkColorScheme(
    primary = Color(DefaultAccentArgb),
    onPrimary = Color(0xFF002A52),
    primaryContainer = Color(0xFF004489),
    onPrimaryContainer = Color(0xFFD8E5FF),
    secondary = Color(0xFF9CD67E),
    onSecondary = Color(0xFF0F3900),
    tertiary = Color(0xFFFFB779),
    onTertiary = Color(0xFF4A1F00),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF111316),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF1E2126),
    onSurfaceVariant = Color(0xFFC8C9CC),
    outline = Color(0xFF6F7479),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF370001),
)

private val CarTypography = Typography(
    displayLarge = TextStyle(fontSize = 128.sp, fontWeight = FontWeight.Light, lineHeight = 128.sp, letterSpacing = (-4).sp),
    displayMedium = TextStyle(fontSize = 80.sp, fontWeight = FontWeight.Light, lineHeight = 88.sp, letterSpacing = (-2).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 34.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    labelMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp, letterSpacing = 1.sp),
)

/** Contrasting on-color for text/icons drawn on top of [accent]. */
private fun onAccent(accent: Color): Color =
    if (accent.luminance() > 0.45f) Color(0xFF06121F) else Color(0xFFF6FAFF)

@Composable
fun FytRadioTheme(accentArgb: Int = DefaultAccentArgb, content: @Composable () -> Unit) {
    val accent = Color(accentArgb)
    val scheme = CarDark.copy(
        primary = accent,
        onPrimary = onAccent(accent),
        primaryContainer = accent,
        onPrimaryContainer = onAccent(accent),
    )
    MaterialTheme(colorScheme = scheme, typography = CarTypography, content = content)
}

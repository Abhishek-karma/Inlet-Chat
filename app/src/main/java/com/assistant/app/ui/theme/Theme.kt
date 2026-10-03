package com.assistant.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.app.R

// Monochrome system: ink on paper in light mode, white on true black in
// dark mode. Neutral grays carry hierarchy; error red is the only hue,
// reserved for failure states.

/** MiSans primary brand typography. */
val AppFontFamily = FontFamily(
    Font(R.font.misans_regular, FontWeight.Normal),
    Font(R.font.misans_medium, FontWeight.Medium),
    Font(R.font.misans_demibold, FontWeight.SemiBold),
)

/** Geist Mono code & metadata font. */
val AppCodeFontFamily = FontFamily(
    Font(R.font.geistmono_regular, FontWeight.Normal),
    Font(R.font.geistmono_italic, FontWeight.Normal, style = FontStyle.Italic),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF111111), // Ink
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFECECEC),
    onPrimaryContainer = Color(0xFF111111),
    secondary = Color(0xFF4A4A4A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E6E6),
    onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = Color(0xFF2E2E2E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE0E0E0),
    onTertiaryContainer = Color(0xFF1A1A1A),
    background = Color(0xFFFFFFFF), // Paper
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF5C5C5C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFEBEBEB),
    surfaceContainerHighest = Color(0xFFE3E3E3),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDEDEDE),
    inverseSurface = Color(0xFF111111),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFF2F2F2),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFFE3E3E3),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2F2F2), // White ink
    onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF242424),
    onPrimaryContainer = Color(0xFFF2F2F2),
    secondary = Color(0xFFB3B3B3),
    onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFF1E1E1E),
    onSecondaryContainer = Color(0xFFE6E6E6),
    tertiary = Color(0xFFD9D9D9),
    onTertiary = Color(0xFF111111),
    tertiaryContainer = Color(0xFF2A2A2A),
    onTertiaryContainer = Color(0xFFE6E6E6),
    background = Color(0xFF000000), // True black, no blue cast
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF171717),
    onSurfaceVariant = Color(0xFF9B9B9B),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF151515),
    surfaceContainerHigh = Color(0xFF1E1E1E),
    surfaceContainerHighest = Color(0xFF282828),
    surfaceBright = Color(0xFF353535),
    surfaceDim = Color(0xFF000000),
    inverseSurface = Color(0xFFEDEDED),
    inverseOnSurface = Color(0xFF111111),
    inversePrimary = Color(0xFF111111),
    outline = Color(0xFF6B6B6B),
    outlineVariant = Color(0xFF1E1E1E),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
)

/**
 * Refined editorial typography ramp.
 * Features generous line-height for body text, monospace for code, and clear headline hierarchy.
 */
private val AppTypography: Typography
    get() = Typography(
        displayLarge = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
        displayMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
        displaySmall = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
        headlineLarge = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp),
        headlineMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
        headlineSmall = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp),
        titleLarge = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
        titleSmall = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp),
        bodyMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
        labelSmall = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
    )

/** Specialized text styles for code blocks and reasoning sections. */
object AppTypographyStyles {
    val code = TextStyle(
        fontFamily = AppCodeFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )
    val reasoning = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )
}

/** Refined Material 3 shapes mapped to AppShape design tokens. */
private val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = AppShape.extraSmall,
    small = AppShape.small,
    medium = AppShape.medium,
    large = AppShape.large,
    extraLarge = AppShape.extraLarge,
)

@Composable
fun ChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

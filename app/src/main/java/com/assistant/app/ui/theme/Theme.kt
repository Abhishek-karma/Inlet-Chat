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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.app.R

// Inlet Chat Brand Palette: Radiant Luminous Teal, Ocean Waves & Obsidian Slate.

/** MiSans typography. */
val AppFontFamily = FontFamily(
    Font(R.font.misans_regular, FontWeight.Normal),
    Font(R.font.misans_medium, FontWeight.Medium),
    Font(R.font.misans_demibold, FontWeight.SemiBold),
)

/** Geist Mono code & metadata font. */
val AppCodeFontFamily = FontFamily(
    Font(R.font.geistmono_regular, FontWeight.Normal),
    Font(R.font.geistmono_italic, FontWeight.Normal, style = androidx.compose.ui.text.font.FontStyle.Italic),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF008080), // Inlet Deep Teal
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0F2F1), // Calm water light teal
    onPrimaryContainer = Color(0xFF002B2B),
    secondary = Color(0xFF4A5D6B), // Slate Blue
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1F5FE),
    onSecondaryContainer = Color(0xFF01579B),
    tertiary = Color(0xFF0D9488),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCFBF1),
    onTertiaryContainer = Color(0xFF115E59),
    background = Color(0xFFFAFBFB), // Calm alabaster/teal mist
    onBackground = Color(0xFF131A1C),
    surface = Color(0xFFFAFBFB),
    onSurface = Color(0xFF131A1C),
    surfaceVariant = Color(0xFFE0E5E5),
    onSurfaceVariant = Color(0xFF5A6264),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F4F4),
    surfaceContainer = Color(0xFFE6EBEB),
    surfaceContainerHigh = Color(0xFFDCE2E2),
    surfaceContainerHighest = Color(0xFFD0D8D8),
    surfaceBright = Color(0xFFFAFBFB),
    surfaceDim = Color(0xFFD5DCDC),
    inverseSurface = Color(0xFF131A1C),
    inverseOnSurface = Color(0xFFFAFBFB),
    inversePrimary = Color(0xFF2DD4BF),
    outline = Color(0xFF8B9596),
    outlineVariant = Color(0xFFDCE2E2),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF2DD4BF), // Radiant Ocean Teal Glow
    onPrimary = Color(0xFF003030),
    primaryContainer = Color(0xFF004D40),
    onPrimaryContainer = Color(0xFFE0F2F1),
    secondary = Color(0xFF8AA1B0), // Soft Slate Blue
    onSecondary = Color(0xFF0F161A),
    secondaryContainer = Color(0xFF1F2C35),
    onSecondaryContainer = Color(0xFFE1F5FE),
    tertiary = Color(0xFF2DD4BF),
    onTertiary = Color(0xFF042F2E),
    tertiaryContainer = Color(0xFF134E4A),
    onTertiaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF0B1012), // Deep Obsidian Sea
    onBackground = Color(0xFFE1E8E8),
    surface = Color(0xFF0B1012),
    onSurface = Color(0xFFE1E8E8),
    surfaceVariant = Color(0xFF1A2224),
    onSurfaceVariant = Color(0xFF909C9D),
    surfaceContainerLowest = Color(0xFF06090A),
    surfaceContainerLow = Color(0xFF11171A),
    surfaceContainer = Color(0xFF1A2224),
    surfaceContainerHigh = Color(0xFF222B2E),
    surfaceContainerHighest = Color(0xFF2E3B3F),
    surfaceBright = Color(0xFF3B4A4F),
    surfaceDim = Color(0xFF0B1012),
    inverseSurface = Color(0xFFE1E8E8),
    inverseOnSurface = Color(0xFF131A1C),
    inversePrimary = Color(0xFF008080),
    outline = Color(0xFF6F7B7C),
    outlineVariant = Color(0xFF222B2E),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
)

/**
 * Editorial, comfortable typography ramp.
 * Body text features generous line-height for effortless long-form reading.
 */
private val AppTypography: Typography
    get() = Typography(
        displayLarge = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 40.sp),
        displayMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 36.sp),
        displaySmall = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp),
        headlineLarge = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
        headlineMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
        headlineSmall = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp),
        titleLarge = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
        titleSmall = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp),
        bodyMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
        labelSmall = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    )

/** Refined organic shapes. */
private val AppShapes = androidx.compose.material3.Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
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

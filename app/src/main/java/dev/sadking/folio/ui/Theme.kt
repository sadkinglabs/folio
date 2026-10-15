package dev.sadking.folio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The fallback palette when wallpaper colours are off: warm paper in light, deep olive in dark.
 * Three surface tiers (ground, panels, lifted) separate by tone, never by outline.
 */
private val folioDark =
    darkColorScheme(
        primary = Color(0xFFC4D3A7),
        onPrimary = Color(0xFF20360C),
        primaryContainer = Color(0xFF2E4419),
        onPrimaryContainer = Color(0xFFD6E8BD),
        secondary = Color(0xFFBEC9AE),
        secondaryContainer = Color(0xFF404A34),
        onSecondaryContainer = Color(0xFFDCE5CD),
        tertiary = Color(0xFFDDC49F),
        onTertiary = Color(0xFF3E2E12),
        tertiaryContainer = Color(0xFF574325),
        onTertiaryContainer = Color(0xFFF8DFBA),
        background = Color(0xFF121410),
        surface = Color(0xFF121410),
        onSurface = Color(0xFFE3E6DA),
        onSurfaceVariant = Color(0xFFC5C9B8),
        surfaceVariant = Color(0xFF2A2E26),
        surfaceContainerLowest = Color(0xFF0D0F0B),
        surfaceContainerLow = Color(0xFF1A1D17),
        surfaceContainer = Color(0xFF20241D),
        surfaceContainerHigh = Color(0xFF2A2E26),
        surfaceContainerHighest = Color(0xFF353930),
        outline = Color(0xFF8F9384),
        outlineVariant = Color(0xFF3E4237),
    )

private val folioLight =
    lightColorScheme(
        primary = Color(0xFF4B6530),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD6E8BD),
        onPrimaryContainer = Color(0xFF102005),
        secondary = Color(0xFF58624C),
        secondaryContainer = Color(0xFFDCE5CD),
        onSecondaryContainer = Color(0xFF17210F),
        tertiary = Color(0xFF7A5E34),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFF8DFBA),
        onTertiaryContainer = Color(0xFF281805),
        background = Color(0xFFF7F6F0),
        surface = Color(0xFFF7F6F0),
        onSurface = Color(0xFF1B1D16),
        onSurfaceVariant = Color(0xFF45483D),
        surfaceVariant = Color(0xFFE2E2D8),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF0EFE7),
        surfaceContainer = Color(0xFFEAE9E0),
        surfaceContainerHigh = Color(0xFFE2E2D8),
        surfaceContainerHighest = Color(0xFFDADAD0),
        outline = Color(0xFF75786B),
        outlineVariant = Color(0xFFCBCBBF),
    )

/** Serif for book and screen titles, system sans for everything else. No third face. */
private val folioTypography =
    Typography(
        displaySmall =
            TextStyle(fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 38.sp),
        headlineLarge =
            TextStyle(fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 38.sp),
        headlineMedium =
            TextStyle(fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 32.sp),
        headlineSmall =
            TextStyle(fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 30.sp),
        titleLarge =
            TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium =
            TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge =
            TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        labelSmall =
            TextStyle(
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.8.sp,
            ),
    )

/** Cards and panels 20 dp, sheets 28 dp, small controls 12 dp. Buttons stay full pills. */
private val folioShapes =
    Shapes(
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
    )

@Composable
fun FolioTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors =
        when {
            dynamic ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> folioDark
            else -> folioLight
        }
    val reducedMotion = systemReducedMotion()
    MaterialExpressiveTheme(
        colorScheme = colors,
        typography = folioTypography,
        shapes = folioShapes,
        content = {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalReducedMotion provides reducedMotion,
                content = content,
            )
        },
    )
}

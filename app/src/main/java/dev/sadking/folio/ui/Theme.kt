package dev.sadking.folio.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FolioTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors =
        when {
            dynamic && Build.VERSION.SDK_INT >= 31 ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark ->
                darkColorScheme(
                    primary = Color(0xFFC4D3A7),
                    onPrimary = Color(0xFF20360C),
                    primaryContainer = Color(0xFF2E4419),
                    onPrimaryContainer = Color(0xFFD6E8BD),
                    secondaryContainer = Color(0xFF404A34),
                    onSecondaryContainer = Color(0xFFDCE5CD),
                    tertiary = Color(0xFFDDC49F),
                    onTertiary = Color(0xFF3E2E12),
                    tertiaryContainer = Color(0xFF574325),
                    onTertiaryContainer = Color(0xFFF8DFBA),
                    onSurface = Color(0xFFE3E6DA),
                    onSurfaceVariant = Color(0xFFC5C9B8),
                    surfaceVariant = Color(0xFF45483D),
                    surfaceContainerLowest = Color(0xFF0D100A),
                    surfaceContainerLow = Color(0xFF181C14),
                    surfaceContainer = Color(0xFF1C2017),
                    surfaceContainerHigh = Color(0xFF272B22),
                    surfaceContainerHighest = Color(0xFF32362C),
                    outline = Color(0xFF8F9384),
                    outlineVariant = Color(0xFF45483D),
                    secondary = Color(0xFFBEC9AE),
                    background = Color(0xFF12150F),
                    surface = Color(0xFF12150F),
                )
            else ->
                lightColorScheme(
                    primary = Color(0xFF46612D),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFD6E8BD),
                    onPrimaryContainer = Color(0xFF102005),
                    secondaryContainer = Color(0xFFDCE5CD),
                    onSecondaryContainer = Color(0xFF17210F),
                    tertiary = Color(0xFF705A3B),
                    onTertiary = Color.White,
                    tertiaryContainer = Color(0xFFF8DFBA),
                    onTertiaryContainer = Color(0xFF281805),
                    onSurface = Color(0xFF1B1D16),
                    onSurfaceVariant = Color(0xFF45483D),
                    surfaceVariant = Color(0xFFE1E5D4),
                    surfaceContainerLowest = Color.White,
                    surfaceContainerLow = Color(0xFFF3F5EC),
                    surfaceContainer = Color(0xFFF0F2E8),
                    surfaceContainerHigh = Color(0xFFE5E8DC),
                    surfaceContainerHighest = Color(0xFFDFE2D5),
                    outline = Color(0xFF75786B),
                    outlineVariant = Color(0xFFC5C9B8),
                    secondary = Color(0xFF58624C),
                    background = Color(0xFFF8FAF0),
                    surface = Color(0xFFF8FAF0),
                )
        }
    val typography =
        Typography(
            displaySmall =
                TextStyle(fontFamily = FontFamily.Serif, fontSize = 36.sp, lineHeight = 42.sp),
            headlineLarge =
                TextStyle(fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 38.sp),
            headlineMedium =
                TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 34.sp),
            headlineSmall =
                TextStyle(fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 30.sp),
            titleLarge =
                TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            titleMedium =
                TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        )
    MaterialExpressiveTheme(
        colorScheme = colors,
        typography = typography,
        shapes =
            Shapes(
                small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            ),
        content = content,
    )
}

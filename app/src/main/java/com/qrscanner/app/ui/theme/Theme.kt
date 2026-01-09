package com.qrscanner.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = SurfaceWhite,
    primaryContainer = PrimaryBlueLight,
    onPrimaryContainer = PrimaryBlueDark,
    secondary = AccentMint,
    onSecondary = SurfaceWhite,
    secondaryContainer = AccentMint.copy(alpha = 0.2f),
    onSecondaryContainer = TextPrimary,
    tertiary = AccentCoral,
    onTertiary = SurfaceWhite,
    tertiaryContainer = AccentCoral.copy(alpha = 0.2f),
    onTertiaryContainer = TextPrimary,
    error = ErrorRed,
    onError = SurfaceWhite,
    errorContainer = ErrorRed.copy(alpha = 0.1f),
    onErrorContainer = ErrorRed,
    background = BackgroundWhite,
    onBackground = TextPrimary,
    surface = SurfaceWhite,
    onSurface = TextPrimary,
    surfaceVariant = CardBackground,
    onSurfaceVariant = TextSecondary,
    outline = TextTertiary,
    outlineVariant = TextTertiary.copy(alpha = 0.5f)
)

@Composable
fun QRScannerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}





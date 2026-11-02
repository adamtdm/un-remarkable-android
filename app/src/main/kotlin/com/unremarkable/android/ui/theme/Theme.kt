package com.unremarkable.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Light Theme: Tactile Warm Paper & Charcoal Ink (matching container app style.css [data-theme="light"])
val PaperBgPrimary = Color(0xFFF6F5F1)       // var(--bg-primary): Warm Tactile Paper
val PaperBgSecondary = Color(0xFFFFFFFF)     // var(--bg-secondary): Pure Card Surface
val PaperBgTertiary = Color(0xFFECEAE3)      // var(--bg-tertiary): Input / Chip Background
val PaperBorder = Color(0xFFDFDDD4)          // var(--border-color)
val PaperBorderStrong = Color(0xFFCAC7BC)    // var(--border-strong)
val CharcoalTextPrimary = Color(0xFF181816)  // var(--text-primary): Deep Charcoal Ink
val CharcoalTextSecondary = Color(0xFF585750)// var(--text-secondary)
val CharcoalTextMuted = Color(0xFF8C8A81)    // var(--text-muted)

// Dark Theme: Nordic Basalt & E-Ink Chalk (matching container app style.css :root dark)
val BasaltBgPrimary = Color(0xFF121211)      // var(--bg-primary): Deep Basalt
val BasaltBgSecondary = Color(0xFF1A1A18)    // var(--bg-secondary): Surface Card
val BasaltBgTertiary = Color(0xFF242422)     // var(--bg-tertiary): Input / Chip Background
val BasaltBorder = Color(0xFF2B2B28)         // var(--border-color)
val BasaltBorderStrong = Color(0xFF3F3E3A)   // var(--border-strong)
val ChalkTextPrimary = Color(0xFFF5F4EF)     // var(--text-primary): E-Ink Chalk Text
val ChalkTextSecondary = Color(0xFFB5B4AC)   // var(--text-secondary)
val ChalkTextMuted = Color(0xFF949289)       // var(--text-muted)

// Status & Accent Tokens from container app
val StatusSuccess = Color(0xFF34D399)        // var(--success)
val StatusWarning = Color(0xFFFBBF24)        // var(--warning)
val StatusDanger = Color(0xFFF87171)         // var(--danger)
val StatusDangerLight = Color(0xFFDC2626)

// Backwards-compatible aliases for ReaderScreen HUD
val Zinc950 = BasaltBgPrimary
val Zinc900 = BasaltBgSecondary
val Zinc800 = BasaltBgTertiary
val Zinc700 = BasaltBorderStrong
val Zinc400 = ChalkTextSecondary
val Zinc200 = PaperBorder
val Zinc100 = PaperBgTertiary
val Amber500 = StatusWarning

private val DarkColorScheme = darkColorScheme(
    primary = ChalkTextPrimary,
    onPrimary = BasaltBgPrimary,
    primaryContainer = BasaltBgTertiary,
    onPrimaryContainer = ChalkTextPrimary,
    surface = BasaltBgSecondary,
    onSurface = ChalkTextPrimary,
    surfaceVariant = BasaltBgTertiary,
    onSurfaceVariant = ChalkTextSecondary,
    background = BasaltBgPrimary,
    onBackground = ChalkTextPrimary,
    outline = BasaltBorder,
    outlineVariant = BasaltBorderStrong,
    secondary = ChalkTextPrimary,
    onSecondary = BasaltBgPrimary,
    tertiary = StatusWarning,
    onTertiary = BasaltBgPrimary
)

private val LightColorScheme = lightColorScheme(
    primary = CharcoalTextPrimary,
    onPrimary = PaperBgSecondary,
    primaryContainer = PaperBgTertiary,
    onPrimaryContainer = CharcoalTextPrimary,
    surface = PaperBgSecondary,
    onSurface = CharcoalTextPrimary,
    surfaceVariant = PaperBgTertiary,
    onSurfaceVariant = CharcoalTextSecondary,
    background = PaperBgPrimary,
    onBackground = CharcoalTextPrimary,
    outline = PaperBorder,
    outlineVariant = PaperBorderStrong,
    secondary = CharcoalTextPrimary,
    onSecondary = PaperBgSecondary,
    tertiary = StatusWarning,
    onTertiary = PaperBgPrimary
)

@Composable
fun UnremarkableTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

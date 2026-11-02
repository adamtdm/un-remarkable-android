package com.unremarkable.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Zinc950 = Color(0xFF09090B)
val Zinc900 = Color(0xFF18181B)
val Zinc800 = Color(0xFF27272A)
val Zinc700 = Color(0xFF3F3F46)
val Zinc400 = Color(0xFFA1A1AA)
val Zinc200 = Color(0xFFE4E4E7)
val Zinc100 = Color(0xFFF4F4F5)
val Amber500 = Color(0xFFF59E0B)

private val DarkColorScheme = darkColorScheme(
    primary = Zinc100,
    onPrimary = Zinc900,
    primaryContainer = Zinc800,
    onPrimaryContainer = Zinc100,
    surface = Zinc900,
    onSurface = Zinc100,
    surfaceVariant = Zinc800,
    onSurfaceVariant = Zinc400,
    background = Zinc950,
    onBackground = Zinc100,
    outline = Zinc700,
    secondary = Amber500,
    onSecondary = Zinc950
)

private val LightColorScheme = lightColorScheme(
    primary = Zinc900,
    onPrimary = Color.White,
    primaryContainer = Zinc200,
    onPrimaryContainer = Zinc900,
    surface = Color.White,
    onSurface = Zinc900,
    surfaceVariant = Zinc100,
    onSurfaceVariant = Zinc700,
    background = Color(0xFFFAFAFA),
    onBackground = Zinc900,
    outline = Zinc200,
    secondary = Amber500,
    onSecondary = Color.White
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

package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = EmeraldLight,
    onPrimary = DarkOasisBackground,
    primaryContainer = DarkOasisSurfaceVariant,
    onPrimaryContainer = DarkTextPrimary,
    secondary = GoldLight,
    onSecondary = Color.Black,
    secondaryContainer = GoldDark,
    onSecondaryContainer = Color.White,
    background = DarkOasisBackground,
    onBackground = DarkTextPrimary,
    surface = DarkOasisSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkOasisSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary
)

private val LightColorScheme = lightColorScheme(
    primary = EmeraldPrimary,
    onPrimary = Color.White,
    primaryContainer = CreamSurfaceVariant,
    onPrimaryContainer = EmeraldDark,
    secondary = GoldAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFEF3C7),
    onSecondaryContainer = GoldDark,
    background = CreamBackground,
    onBackground = LightTextPrimary,
    surface = CreamSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = CreamSurfaceVariant,
    onSurfaceVariant = LightTextSecondary
)

@Composable
fun QuranAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkTerminalScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = Color(0xFF001F26),
    primaryContainer = Color(0xFF083344),
    onPrimaryContainer = Color(0xFFCFFAFE),
    secondary = BullishGreen,
    onSecondary = Color(0xFF002114),
    tertiary = NeutralAmber,
    background = TerminalBgDark,
    onBackground = TextPrimaryDark,
    surface = TerminalSurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = TerminalSurfaceVariantDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = TerminalBorderDark,
    error = BearishRed,
    onError = Color.White
)

private val LightTerminalScheme = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF075985),
    secondary = Color(0xFF059669),
    onSecondary = Color.White,
    tertiary = Color(0xFFD97706),
    background = TerminalBgLight,
    onBackground = TextPrimaryLight,
    surface = TerminalSurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = TerminalSurfaceVariantLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = TerminalBorderLight,
    error = Color(0xFFDC2626),
    onError = Color.White
)

@Composable
fun NexSyncTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkTerminalScheme else LightTerminalScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    NexSyncTheme(darkTheme = darkTheme, content = content)
}

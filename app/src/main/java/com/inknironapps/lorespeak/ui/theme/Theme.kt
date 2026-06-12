package com.inknironapps.lorespeak.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Ink & Iron Apps palette
private val InkBackground = Color(0xFF0F1115)
private val Paper = Color(0xFFE8E4DA)
private val Teal = Color(0xFF095F73)
private val TealBright = Color(0xFF3BB3C9)
private val Muted = Color(0xFF9CA3AF)

private val DarkColors = darkColorScheme(
    primary = TealBright,
    onPrimary = InkBackground,
    secondary = Teal,
    background = InkBackground,
    onBackground = Paper,
    surface = Color(0xFF161A20),
    onSurface = Paper,
    onSurfaceVariant = Muted,
)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Paper,
    secondary = TealBright,
    background = Paper,
    onBackground = InkBackground,
    surface = Color(0xFFF3F0E8),
    onSurface = InkBackground,
    onSurfaceVariant = Color(0xFF5B5F66),
)

@Composable
fun LoreSpeakTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

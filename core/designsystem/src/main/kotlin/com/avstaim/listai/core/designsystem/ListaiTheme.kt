package com.avstaim.listai.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF62503C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9D5BA),
    onPrimaryContainer = Color(0xFF2B1B0C),
    secondary = Color(0xFF615847),
    background = Color(0xFFF7F1E8),
    onBackground = Color(0xFF201B16),
    surface = Color(0xFFFFF8EF),
    onSurface = Color(0xFF201B16),
    surfaceVariant = Color(0xFFEAE1D5),
    onSurfaceVariant = Color(0xFF4C463E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD5BA98),
    onPrimary = Color(0xFF382A1B),
    primaryContainer = Color(0xFF50402E),
    onPrimaryContainer = Color(0xFFF2D8B5),
    secondary = Color(0xFFCEC3AF),
    background = Color(0xFF17130F),
    onBackground = Color(0xFFECE1D6),
    surface = Color(0xFF211C17),
    onSurface = Color(0xFFECE1D6),
    surfaceVariant = Color(0xFF4C463E),
    onSurfaceVariant = Color(0xFFD0C5B9),
)

@Composable
fun ListaiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}


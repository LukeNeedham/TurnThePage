package com.avstaim.listai.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF355F84),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E7F5),
    onPrimaryContainer = Color(0xFF17354C),
    secondary = Color(0xFF526D83),
    secondaryContainer = Color(0xFFDDE7EF),
    onSecondaryContainer = Color(0xFF243C51),
    outline = Color(0xFF8397A7),
    outlineVariant = Color(0xFFCAD7E1),
    background = Color(0xFFBFCEDB),
    onBackground = Color(0xFF203447),
    surface = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD5DFE8),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F6FA),
    surfaceContainer = Color(0xFFECF2F7),
    surfaceContainerHigh = Color(0xFFE6EDF3),
    surfaceContainerHighest = Color(0xFFDFE8F0),
    onSurface = Color(0xFF203447),
    surfaceVariant = Color(0xFFE6EDF3),
    onSurfaceVariant = Color(0xFF526778),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA6CAE8),
    onPrimary = Color(0xFF183449),
    primaryContainer = Color(0xFF354F67),
    onPrimaryContainer = Color(0xFFDAEAF7),
    secondary = Color(0xFFADC3D5),
    secondaryContainer = Color(0xFF334B60),
    onSecondaryContainer = Color(0xFFDCE8F1),
    outline = Color(0xFF778D9F),
    outlineVariant = Color(0xFF475C6E),
    background = Color(0xFF19242F),
    onBackground = Color(0xFFDEE7F0),
    surface = Color(0xFF25313D),
    surfaceDim = Color(0xFF18232D),
    surfaceBright = Color(0xFF3B4B5B),
    surfaceContainerLowest = Color(0xFF121D27),
    surfaceContainerLow = Color(0xFF202C38),
    surfaceContainer = Color(0xFF25313D),
    surfaceContainerHigh = Color(0xFF2E3D4B),
    surfaceContainerHighest = Color(0xFF384A5B),
    onSurface = Color(0xFFDEE7F0),
    surfaceVariant = Color(0xFF526778),
    onSurfaceVariant = Color(0xFFB7C9D8),
)

@Composable
fun ListaiTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

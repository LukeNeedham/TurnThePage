package com.avstaim.listai.core.model

enum class ReaderPinchMode { FONT_SIZE, TEMPORARY_ZOOM, DISABLED }

enum class ReaderTheme { LIGHT, DARK, SYSTEM }
enum class ReaderScreenTimeoutMode { ALWAYS_ON, SYSTEM }

data class ReaderPreferences(
    val fontSizeSp: Float = 18f,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val showClock: Boolean = true,
    val screenTimeoutMode: ReaderScreenTimeoutMode = ReaderScreenTimeoutMode.ALWAYS_ON,
    val pinchMode: ReaderPinchMode = ReaderPinchMode.FONT_SIZE,
) {
    init { require(fontSizeSp.isFinite() && fontSizeSp in 12f..32f) }
}

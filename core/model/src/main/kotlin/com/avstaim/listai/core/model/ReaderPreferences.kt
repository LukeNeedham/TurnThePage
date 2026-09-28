package com.avstaim.listai.core.model

enum class ReaderTheme { LIGHT, DARK, SYSTEM }

data class ReaderPreferences(
    val fontSizeSp: Float = 18f,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val showClock: Boolean = true,
) {
    init { require(fontSizeSp.isFinite() && fontSizeSp in 12f..32f) }
}

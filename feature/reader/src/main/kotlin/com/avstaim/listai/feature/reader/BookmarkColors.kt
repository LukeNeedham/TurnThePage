package com.avstaim.listai.feature.reader

import androidx.compose.ui.graphics.Color
import com.avstaim.listai.core.model.Bookmark

private val LightBookmarkColors = listOf(
    Color(0xFF356C91),
    Color(0xFF447773),
    Color(0xFF666797),
    Color(0xFF865F7A),
    Color(0xFF6A7353),
    Color(0xFF4D7188),
    Color(0xFF785F91),
    Color(0xFF3F7A8A),
)

private val DarkBookmarkColors = listOf(
    Color(0xFF82B5D8),
    Color(0xFF83B3AD),
    Color(0xFFA5A4D2),
    Color(0xFFC297AE),
    Color(0xFFAAB58A),
    Color(0xFF91B1C6),
    Color(0xFFAE9AC7),
    Color(0xFF83B8C5),
)

/**
 * A bookmark keeps the same cloth colour without adding a persistence-only schema field.
 * Palette order and size are a compatibility contract: changing either recolours saved marks.
 */
internal fun bookmarkColor(bookmark: Bookmark, darkTheme: Boolean): Color {
    val palette = if (darkTheme) DarkBookmarkColors else LightBookmarkColors
    return palette[bookmarkColorIndex(bookmark.id, palette.size)]
}

internal fun bookmarkInkColor(darkTheme: Boolean): Color =
    if (darkTheme) Color(0xFF172632) else Color(0xFFF4F8FB)

internal fun bookmarkColorIndex(id: String, paletteSize: Int = LightBookmarkColors.size): Int {
    require(paletteSize > 0)
    var hash = 0x811C9DC5u
    id.forEach { character ->
        hash = (hash xor character.code.toUInt()) * 0x01000193u
    }
    return (hash % paletteSize.toUInt()).toInt()
}

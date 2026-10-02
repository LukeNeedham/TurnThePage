package com.avstaim.listai.feature.library

import androidx.compose.runtime.saveable.listSaver

internal data class LibraryViewportAnchor(val key: String, val offset: Int)

/** Retains hidden origins too: resizing a search must not lose the root shelf's visible ID. */
internal class LibraryViewportState {
    private val anchors = mutableMapOf<String, LibraryViewportAnchor>()
    fun record(surface: String, key: String?, offset: Int) {
        if (key != null) anchors[surface] = LibraryViewportAnchor(key, offset.coerceAtLeast(0))
    }
    fun anchor(surface: String): LibraryViewportAnchor? = anchors[surface]

    companion object {
        val Saver = listSaver<LibraryViewportState, Any>(
            save = { state -> state.anchors.flatMap { (surface, anchor) -> listOf(surface, anchor.key, anchor.offset) } },
            restore = { values -> LibraryViewportState().apply {
                values.chunked(3).forEach { parts -> record(parts[0] as String, parts[1] as String, parts[2] as Int) }
            } },
        )
    }
}

internal fun libraryAnchorRow(keys: List<String>, columns: Int, anchor: LibraryViewportAnchor): Int? =
    keys.indexOf(anchor.key).takeIf { it >= 0 }?.div(columns.coerceAtLeast(1))

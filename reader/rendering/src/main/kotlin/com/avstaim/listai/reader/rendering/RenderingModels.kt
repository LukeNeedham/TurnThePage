package com.avstaim.listai.reader.rendering

import com.avstaim.listai.core.model.PageId
import com.avstaim.listai.reader.pagination.PageMode

data class BookViewport(
    val widthPx: Int,
    val heightPx: Int,
    val pageMode: PageMode,
) {
    init {
        require(widthPx > 0 && heightPx > 0) { "Viewport dimensions must be positive" }
    }
}

interface PageSurface {
    val pageId: PageId
}

data class RenderedPage(
    val surface: PageSurface,
)

data class BookScene(
    val previous: RenderedPage?,
    val left: RenderedPage?,
    val right: RenderedPage?,
    val next: RenderedPage?,
    val progress: Float,
) {
    init {
        require(progress in 0f..1f) { "Progress must be in 0..1" }
    }
}


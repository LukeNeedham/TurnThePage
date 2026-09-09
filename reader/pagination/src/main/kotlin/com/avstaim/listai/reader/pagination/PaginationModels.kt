package com.avstaim.listai.reader.pagination

import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.PageId

enum class PageMode {
    SINGLE,
    SPREAD,
}

data class LayoutKey(
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val pageMode: PageMode,
    val fontFamilyId: String,
    val fontSizeSp: Float,
    val lineHeightMultiplier: Float,
    val paragraphSpacingDp: Float,
    val horizontalMarginsDp: Float,
    val verticalMarginsDp: Float,
    val textScale: Float,
    val densityKey: String,
    val themeMetricsVersion: Int,
) {
    init {
        require(viewportWidthPx > 0 && viewportHeightPx > 0) {
            "Viewport dimensions must be positive"
        }
        require(fontSizeSp > 0f) { "Font size must be positive" }
        require(lineHeightMultiplier > 0f) { "Line height must be positive" }
        require(textScale > 0f) { "Text scale must be positive" }
    }
}

data class PageBlockLayout(
    val blockId: BlockId,
    val startCharOffset: Int,
    val endCharOffsetExclusive: Int,
) {
    init {
        require(startCharOffset >= 0) { "Start offset must not be negative" }
        require(endCharOffsetExclusive >= startCharOffset) { "Invalid source range" }
    }
}

data class PageLayout(
    val id: PageId,
    val start: BookLocation,
    val endExclusive: BookLocation,
    val blocks: List<PageBlockLayout>,
    val displayNumber: Int,
)

interface PaginationEngine {
    suspend fun pageContaining(
        document: BookDocument,
        location: BookLocation,
        layoutKey: LayoutKey,
    ): PageLayout
}


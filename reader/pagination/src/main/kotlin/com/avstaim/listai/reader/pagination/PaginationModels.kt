package com.avstaim.listai.reader.pagination

import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.PageId
import kotlinx.coroutines.flow.Flow

enum class PageMode {
    SINGLE,
    SPREAD,
}

enum class HyphenationMode { NONE, NORMAL, FULL }

data class LayoutKey(
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val pageMode: PageMode,
    val fontFamilyId: String = "serif",
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.35f,
    val paragraphSpacingDp: Float = 8f,
    val horizontalMarginsDp: Float = 24f,
    val verticalMarginsDp: Float = 28f,
    val textScale: Float = 1f,
    val densityKey: String = "1.0",
    val themeMetricsVersion: Int = 1,
    val darkTheme: Boolean = false,
    val hyphenationMode: HyphenationMode = HyphenationMode.NORMAL,
    val density: Float = 1f,
    val localeTag: String = "en",
    val pageWidthPx: Int = if (pageMode == PageMode.SPREAD) viewportWidthPx / 2 else viewportWidthPx,
    val pageHeightPx: Int = viewportHeightPx,
) {
    init {
        require(viewportWidthPx > 0 && viewportHeightPx > 0 && pageWidthPx > 0 && pageHeightPx > 0) {
            "Viewport dimensions must be positive"
        }
        require(listOf(fontSizeSp, lineHeightMultiplier, textScale, density).all { it.isFinite() && it > 0 })
        require(listOf(paragraphSpacingDp, horizontalMarginsDp, verticalMarginsDp).all { it.isFinite() && it >= 0 })
        require(fontFamilyId.isNotBlank() && densityKey.isNotBlank() && localeTag.isNotBlank())
    }

    val marginX: Float get() = (horizontalMarginsDp * density).coerceAtMost(pageWidthPx * .22f)
    val marginY: Float get() = (verticalMarginsDp * density).coerceAtMost(pageHeightPx * .18f)
    val contentWidth: Int get() = (pageWidthPx - marginX * 2).toInt().coerceAtLeast(1)
    val contentHeight: Float get() = (pageHeightPx - marginY * 2 - footerHeight).coerceAtLeast(1f)
    val footerHeight: Float get() = (24f * density).coerceAtMost(pageHeightPx * .12f)
    val paragraphGap: Float get() = paragraphSpacingDp * density
}

/** Quote wrappers do not duplicate source offsets. Coordinates are UTF-16, as in BookLocation. */
data class FlowBlock(val source: BookBlock, val globalOffset: Long, val quoteDepth: Int = 0)

data class MeasuredLine(val start: Int, val endExclusive: Int, val top: Float, val bottom: Float) {
    init {
        require(start >= 0 && endExclusive >= start)
        require(top.isFinite() && bottom.isFinite() && bottom > top)
    }
    val height: Float get() = bottom - top
}

/** May own an immutable platform layout. The engine has no dependency on its drawing API. */
interface BlockMeasurement {
    val lines: List<MeasuredLine>
    val width: Float
}

/** The persistent index must not retain platform text layouts, paints, bitmaps or decoded images. */
data class MeasurementMetrics(override val lines: List<MeasuredLine>, override val width: Float) : BlockMeasurement

fun interface BlockMeasurer {
    fun measure(block: FlowBlock, key: LayoutKey): BlockMeasurement
}

data class PageBlockLayout(
    val blockId: BlockId,
    val startCharOffset: Int,
    val endCharOffsetExclusive: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val firstLine: Int,
    val endLineExclusive: Int,
    val scale: Float,
    val measurement: BlockMeasurement,
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
    val chapterId: BlockId? = null,
)

/** Immutable prefix of complete pages. A provisional tail is not the end of the book. */
data class PaginationSnapshot(
    val key: LayoutKey,
    val pages: List<PageLayout>,
    val anchorPageIndex: Int,
    val complete: Boolean,
)

interface PaginationEngine {
    fun paginate(document: BookDocument, location: BookLocation?, layoutKey: LayoutKey): Flow<PaginationSnapshot>
    suspend fun pageContaining(
        document: BookDocument,
        location: BookLocation,
        layoutKey: LayoutKey,
    ): PageLayout
}

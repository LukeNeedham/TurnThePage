package com.avstaim.listai.feature.reader.bookview

import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.CoverPosition
import com.avstaim.listai.core.model.PageId
import com.avstaim.listai.core.model.ReadingProgress
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.MeasurementMetrics
import com.avstaim.listai.reader.pagination.PageBlockLayout
import com.avstaim.listai.reader.pagination.PageLayout
import com.avstaim.listai.reader.pagination.PageMode
import com.avstaim.listai.reader.pagination.PaginationSnapshot
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import org.junit.Assert.*
import org.junit.Test

class ReaderJumpTest {
    @Test fun farTargetWaitsForItsRealPageInsteadOfClampingToThePrefix() {
        val request = ReaderJumpRequest(1, progress(95))
        assertNull(resolveReaderJump(request, snapshot(3, complete = false), null, null, BookPresentation.SINGLE))
        val resolved = requireNotNull(resolveReaderJump(request, snapshot(10), null, null, BookPresentation.SINGLE))
        assertEquals(PhysicalBookState.Open(9), resolved.target)
        assertEquals(location(95), resolved.request.progress.location)
    }

    @Test fun incompleteSpreadWaitsForItsRightLeafWithoutInventingABackCover() {
        val request = ReaderJumpRequest(2, progress(35))
        assertNotNull(resolveReaderJump(request, snapshot(4, complete = false), null, null, BookPresentation.SINGLE))
        assertNull(resolveReaderJump(request, snapshot(4, complete = false), null, null, BookPresentation.SPREAD))
        assertEquals(PhysicalBookState.Open(3),
            resolveReaderJump(request, snapshot(5, complete = false), null, null, BookPresentation.SPREAD)?.target)
        // A genuinely terminal left leaf is allowed to face the inner back cover.
        assertNotNull(resolveReaderJump(request, snapshot(4), null, null, BookPresentation.SPREAD))
    }

    @Test fun physicalContentsOrderingWinsOverRetainedSourceOffsets() {
        val source = progress(95)
        val contents = source.copy(tocEntryIndex = 0)
        assertEquals(TurnDirection.BACKWARD, readerJumpDirection(PhysicalBookState.Open(9),
            PhysicalBookState.Open(0), source, contents))
        assertEquals(TurnDirection.FORWARD, readerJumpDirection(PhysicalBookState.Open(0),
            PhysicalBookState.Open(1), contents, progress(0)))
    }

    @Test fun exactNavigationWithinOneLeafUsesSemanticDirection() {
        assertEquals(TurnDirection.FORWARD, readerJumpDirection(PhysicalBookState.Open(1),
            PhysicalBookState.Open(1), progress(12), progress(13)))
        assertEquals(TurnDirection.BACKWARD, readerJumpDirection(PhysicalBookState.Open(1),
            PhysicalBookState.Open(1), progress(13), progress(12)))
        assertEquals(TurnDirection.BACKWARD, readerJumpDirection(PhysicalBookState.Open(1),
            PhysicalBookState.Open(1), progress(0).copy(tocEntryIndex = 2, tocCharOffset = 9),
            progress(0).copy(tocEntryIndex = 2, tocCharOffset = 8)))
    }

    @Test fun aMillionPageJumpPinsOnlyTwoVisibleSpreadsAndRetainsTheRightHandSemanticTarget() {
        val source = PhysicalBookState.Open(8)
        val target = PhysicalBookState.Open(999_998)
        assertEquals(listOf(7, 8, 999_997, 999_998),
            readerJumpSurfaceIndices(source, target, BookPresentation.SPREAD, 1_000_000))
        assertEquals(listOf(8, 999_998),
            readerJumpSurfaceIndices(source, target, BookPresentation.SINGLE, 1_000_000))
        assertEquals(999_998, target.semanticPageIndex)
    }

    @Test fun completedJumpPreservesExactCharacterAndContentsReturnAnchor() {
        val position = ReaderPosition(location(12))
        val requested = progress(17)
        // Merely preparing the request must leave the current checkpoint untouched.
        resolveReaderJump(ReaderJumpRequest(3, requested), snapshot(3), null, null, BookPresentation.SPREAD)
        assertEquals(location(12), position.location)
        position.commitJump(requested)
        assertEquals(location(17), position.location)
        position.commitJump(requested.copy(tocEntryIndex = 3, tocCharOffset = 7))
        assertEquals(location(17), position.location)
        assertEquals(3, position.tocEntryIndex)
        assertEquals(7, position.tocCharOffset)
        position.commitJump(requested)
        assertEquals(location(17), position.progress(100).location)
        assertNull(position.tocEntryIndex)
    }

    private fun location(offset: Int) = BookLocation(BlockId("source"), offset, offset.toLong())
    private fun progress(offset: Int) = ReadingProgress(location(offset), CoverPosition.OPEN, offset / 100f)
    private fun snapshot(count: Int, complete: Boolean = true) = PaginationSnapshot(
        LayoutKey(400, 800, PageMode.SINGLE), (0 until count).map { index ->
            PageLayout(PageId(index.toLong()), location(index * 10), location((index + 1) * 10),
                listOf(PageBlockLayout(BlockId("source"), index * 10, (index + 1) * 10,
                    0f, 0f, 100f, 100f, 0, 1, 1f, MeasurementMetrics(emptyList(), 100f))), index + 1)
        }, 0, complete)
}

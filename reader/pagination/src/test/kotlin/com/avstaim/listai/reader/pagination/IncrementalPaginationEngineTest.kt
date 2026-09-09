package com.avstaim.listai.reader.pagination

import com.avstaim.listai.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IncrementalPaginationEngineTest {
    private val key = LayoutKey(240, 200, PageMode.SINGLE, horizontalMarginsDp = 10f,
        verticalMarginsDp = 10f, paragraphSpacingDp = 4f)
    private val measurer = BlockMeasurer { block, layout ->
        val text = block.source.plainText()
        val charsPerLine = (layout.contentWidth / (layout.fontSizeSp * layout.textScale * layout.density * .5f)).toInt().coerceAtLeast(1)
        val height = layout.fontSizeSp * layout.lineHeightMultiplier * layout.textScale * layout.density
        val ranges = if (block.source is BookBlock.Image) listOf(0 to text.length) else
            if (text.isEmpty()) listOf(0 to 0) else text.indices.step(charsPerLine).map { it to minOf(text.length, it + charsPerLine) }
        object : BlockMeasurement {
            override val width = layout.contentWidth.toFloat()
            override val lines = ranges.mapIndexed { i, (start, end) ->
                MeasuredLine(start, end, i * height, (i + 1) * height)
            }
        }
    }
    private val engine = IncrementalPaginationEngine(measurer)
    private fun paragraph(id: String, length: Int) = BookBlock.Paragraph(BlockId(id), listOf(InlineNode.Text("а".repeat(length))))
    private fun book(vararg blocks: BookBlock) = BookDocument(BookId("test"), BookMetadata("Test"), blocks.toList())
    private val longBook get() = book(*Array(60) { paragraph("p$it", 600) })

    @Test fun deterministicBoundariesAndDisplayNumbers() = runBlocking {
        val doc = longBook
        val a = engine.paginate(doc, null, key).last().pages
        val b = engine.paginate(doc, null, key).last().pages
        assertEquals(a.map { it.start to it.endExclusive }, b.map { it.start to it.endExclusive })
        assertEquals((1..a.size).toList(), a.map { it.displayNumber })
        assertEquals(a.size, a.map { it.id }.toSet().size)
    }

    @Test fun paragraphFragmentsCoverSourceExactlyWithoutOverlapsOrMissingCharacters() = runBlocking {
        val source = paragraph("body", 1801)
        val pages = engine.paginate(book(source), null, key).last().pages
        assertTrue(pages.size > 2)
        val fragments = pages.flatMap { it.blocks }
        assertEquals(0, fragments.first().startCharOffset)
        assertEquals(1801, fragments.last().endCharOffsetExclusive)
        fragments.zipWithNext().forEach { (a, b) -> assertEquals(a.endCharOffsetExclusive, b.startCharOffset) }
        fragments.forEach { fragment ->
            assertTrue(fragment.y >= key.marginY)
            assertTrue(fragment.y + fragment.height <= key.marginY + key.contentHeight + .001f)
            assertEquals(fragment.startCharOffset, fragment.measurement.lines[fragment.firstLine].start)
            assertEquals(fragment.endCharOffsetExclusive, fragment.measurement.lines[fragment.endLineExclusive - 1].endExclusive)
        }
    }

    @Test fun headingMovesWithFirstFollowingLine() = runBlocking {
        val heading = BookBlock.Heading(BlockId("h"), 1, listOf(InlineNode.Text("Chapter")))
        val doc = book(paragraph("before", 100), heading, paragraph("after", 200))
        val pages = engine.paginate(doc, null, key).last().pages
        val headingPage = pages.first { p -> p.blocks.any { it.blockId == heading.id } }
        assertEquals(heading.id, headingPage.blocks.first().blockId)
        assertTrue(headingPage.blocks.any { it.blockId == BlockId("after") })
        assertEquals(heading.id, headingPage.chapterId)
    }

    @Test fun oversizedImageIsSingleScaledFragmentAndDoesNotCropOrLoop() = runBlocking {
        val image = BookBlock.Image(BlockId("image"), ImageId("image"), "Illustration")
        val imageEngine = IncrementalPaginationEngine { block, layout ->
            if (block.source !is BookBlock.Image) measurer.measure(block, layout) else object : BlockMeasurement {
                override val width = 100f
                override val lines = listOf(MeasuredLine(0, 12, 0f, 1000f))
            }
        }
        val pages = imageEngine.paginate(book(paragraph("before", 20), image, paragraph("after", 20)), null, key).last().pages
        val fragment = pages.flatMap { it.blocks }.single { it.blockId == image.id }
        assertEquals(key.contentHeight, fragment.height, .001f)
        assertTrue(fragment.scale < 1f)
        assertEquals(100f * fragment.scale, fragment.width, .001f)
    }

    @Test fun semanticAnchorSurvivesSingleSpreadResizeFontAndMargins() = runBlocking {
        val doc = longBook
        val anchor = BookLocation(BlockId("p15"), 371, 0) // Deliberately stale fallback.
        val keys = listOf(key, key.copy(pageMode = PageMode.SPREAD, viewportWidthPx = 600, pageWidthPx = 290),
            key.copy(pageHeightPx = 350), key.copy(fontSizeSp = 24f), key.copy(horizontalMarginsDp = 35f),
            key.copy(lineHeightMultiplier = 1.7f), key.copy(textScale = 1.3f))
        for (layout in keys) {
            val page = engine.pageContaining(doc, anchor, layout)
            assertTrue(page.blocks.any { it.blockId == anchor.blockId &&
                it.startCharOffset <= anchor.charOffset && it.endCharOffsetExclusive > anchor.charOffset })
        }
    }

    @Test fun exactBoundaryUsesFollowingPageAndEofUsesFinalPage() = runBlocking {
        val doc = book(paragraph("p", 2000))
        val pages = engine.paginate(doc, null, key).last().pages
        assertEquals(1, pages.pageIndexContaining(pages[1].start))
        assertEquals(pages.lastIndex, pages.pageIndexContaining(pages.last().endExclusive))
    }

    @Test fun unknownBlockFallsBackAndOffsetsClamp() {
        val flow = book(paragraph("a", 20), paragraph("b", 40)).readingFlow()
        assertEquals(BookLocation(BlockId("b"), 9, 30), flow.resolveLocation(BookLocation(BlockId("deleted"), 0, 30)))
        assertEquals(BookLocation(BlockId("a"), 20, 20), flow.resolveLocation(BookLocation(BlockId("a"), 999, 0)))
    }

    @Test fun quoteAndPoemOffsetsMatchModelAndNotesAreNotPaginated() {
        val poem = BookBlock.Poem(BlockId("poem"), listOf(BookPoemStanza(listOf(listOf(InlineNode.Text("a")), listOf(InlineNode.Text("b"))))),
            title = listOf(listOf(InlineNode.Text("Title"))))
        val doc = book(BookBlock.Quote(BlockId("quote"), listOf(paragraph("q", 4))), poem)
            .copy(notes = listOf(BookNote(NoteId("note"), listOf(paragraph("noteBody", 20)))))
        val flow = doc.readingFlow()
        assertEquals(listOf(BlockId("q"), poem.id), flow.map { it.source.id })
        assertEquals(5L, flow[1].globalOffset)
        assertEquals(1, flow.first().quoteDepth)
        assertEquals(poem.plainText(), poem.textContent().plainText())
    }

    @Test fun incrementalPrefixesAreImmutableCompleteAndHaveAnchorNeighbours() = runBlocking {
        val snapshots = engine.paginate(longBook, BookLocation(BlockId("p3"), 150, 0), key).toList()
        assertTrue(snapshots.size > 2)
        assertFalse(snapshots.first().complete)
        assertTrue(snapshots.last().complete)
        assertTrue(snapshots.first().pages.size >= snapshots.first().anchorPageIndex + 4)
        snapshots.zipWithNext().forEach { (a, b) -> assertEquals(a.pages, b.pages.take(a.pages.size)) }
    }

    @Test fun cancellingAfterFirstPrefixDoesNotMeasureWholeBook() = runBlocking {
        var measured = 0
        val counting = IncrementalPaginationEngine { block, layout -> measured++; measurer.measure(block, layout) }
        counting.paginate(longBook, null, key).first()
        assertTrue(measured < 10)
    }

    @Test fun cancellationIsNotConvertedIntoAResult() = runBlocking {
        val cancelling = IncrementalPaginationEngine { _, _ -> throw CancellationException("obsolete") }
        try { cancelling.paginate(longBook, null, key).last(); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
    }

    @Test fun everyMetricIsPartOfLayoutIdentityAndInvalidMetricsAreRejected() {
        val changes = listOf(key.copy(viewportWidthPx = 300), key.copy(viewportHeightPx = 300),
            key.copy(pageMode = PageMode.SPREAD), key.copy(pageWidthPx = 300), key.copy(pageHeightPx = 300),
            key.copy(fontFamilyId = "sans-serif"), key.copy(fontSizeSp = 19f), key.copy(lineHeightMultiplier = 1.4f),
            key.copy(paragraphSpacingDp = 9f), key.copy(horizontalMarginsDp = 15f), key.copy(verticalMarginsDp = 15f),
            key.copy(textScale = 1.1f), key.copy(densityKey = "2"), key.copy(density = 2f),
            key.copy(themeMetricsVersion = 2), key.copy(hyphenationMode = HyphenationMode.FULL), key.copy(localeTag = "ru"))
        changes.forEach { assertNotEquals(key, it) }
        listOf<() -> LayoutKey>({ key.copy(fontSizeSp = Float.NaN) }, { key.copy(pageWidthPx = 0) },
            { key.copy(horizontalMarginsDp = -1f) }, { key.copy(density = Float.POSITIVE_INFINITY) }).forEach { invalid ->
            try { invalid(); fail("Invalid key accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun tinyViewportAndEmptyTextTerminateWithoutDroppingBlocks() = runBlocking {
        val doc = book(paragraph("empty", 0), BookBlock.Spacer(BlockId("space")), paragraph("text", 20))
        val pages = engine.paginate(doc, null, key.copy(pageWidthPx = 1, pageHeightPx = 1)).last().pages
        assertEquals(doc.blocks.map { it.id }.toSet(), pages.flatMap { it.blocks }.map { it.blockId }.toSet())
    }
}

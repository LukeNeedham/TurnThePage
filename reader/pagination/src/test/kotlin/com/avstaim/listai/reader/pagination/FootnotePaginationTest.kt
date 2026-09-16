package com.avstaim.listai.reader.pagination

import com.avstaim.listai.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FootnotePaginationTest {
    private val key = LayoutKey(240, 300, PageMode.SINGLE, horizontalMarginsDp = 10f,
        verticalMarginsDp = 10f, paragraphSpacingDp = 4f)
    private fun paragraph(id: String, content: List<InlineNode>) = BookBlock.Paragraph(BlockId(id), content)
    private fun text(value: String) = InlineNode.Text(value)
    private fun call(id: String, marker: String = "*") = InlineNode.Link(LinkTarget.Note(NoteId(id)), listOf(text(marker)))
    private fun note(id: String, length: Int) = BookNote(NoteId(id), listOf(paragraph("body:$id", listOf(text("n".repeat(length))))))
    private fun book(content: List<InlineNode>, notes: List<BookNote>) = BookDocument(BookId("test"),
        BookMetadata("Test"), listOf(paragraph("p", content)), notes = notes)
    private val measurer = BlockMeasurer { block, layout ->
        val preview = block.source.id.value.startsWith("listai:preview:")
        val text = block.source.plainText()
        val width = if (preview) 20 else 10
        val height = if (preview) 10f else 20f
        val starts = if (text.isEmpty()) listOf(0) else text.indices.step(width).toList()
        object : BlockMeasurement {
            override val width: Float = layout.contentWidth.toFloat()
            override val lines = starts.mapIndexed { i, start ->
                MeasuredLine(start, minOf(start + width, text.length), i * height, (i + 1) * height)
            }
        }
    }
    private val engine = IncrementalPaginationEngine(measurer)

    private fun assertNoOverlaps(pages: List<PageLayout>, layout: LayoutKey = key) {
        pages.forEach { page ->
            val bottom = layout.contentTop + layout.contentHeight
            page.blocks.forEach { assertTrue(it.y + it.height <= bottom + .001f) }
            page.footnotes.forEach { assertTrue(it.y + it.height <= bottom + .001f) }
            if (page.footnotes.isNotEmpty()) {
                assertTrue(page.blocks.maxOf { it.y + it.height } <= page.footnotes.first().y + .001f)
                page.footnotes.zipWithNext().forEach { (a, b) -> assertTrue(a.y + a.height <= b.y + .001f) }
                assertTrue(bottom - page.footnotes.first().y <= layout.contentHeight * .4f + .001f)
            }
        }
    }

    @Test fun shortNoteIsWholeBottomAlignedAndFollowsExactSourceCall() = runBlocking {
        val doc = book(listOf(text("a".repeat(15)), call("n"), text("b".repeat(300))), listOf(note("n", 30)))
        val pages = engine.paginate(doc, null, key).last().pages
        val footer = pages.first().footnotes.single()
        assertEquals(NoteId("n"), footer.noteId)
        assertEquals(BookLocation(BlockId("p"), 15, 15), footer.source)
        assertEquals(30, footer.endCharOffsetExclusive)
        assertFalse(footer.truncated)
        assertEquals(key.contentTop + key.contentHeight, footer.y + footer.height, .001f)
        assertTrue(pages.drop(1).all { it.footnotes.isEmpty() })
        assertTrue(footer.measurement is MeasurementMetrics)
        assertNoOverlaps(pages)
        val fragments = pages.flatMap { it.blocks }
        assertEquals(0, fragments.first().startCharOffset)
        assertEquals(316, fragments.last().endCharOffsetExclusive)
        fragments.zipWithNext().forEach { (a, b) -> assertEquals(a.endCharOffsetExclusive, b.startCharOffset) }
    }

    @Test fun longNotesUseThreeOrFourLinesAndRetainRichContent() = runBlocking {
        val rich = BookNote(NoteId("n"), listOf(paragraph("note:n", listOf(InlineNode.Strong(listOf(text("long ".repeat(100))))))))
        val doc = book(listOf(call("n"), text("a".repeat(400))), listOf(rich))
        val first = engine.paginate(doc, null, key).last().pages.first().footnotes.single()
        val second = engine.paginate(doc, null, key).last().pages.first().footnotes.single()
        assertEquals(first, second)
        assertTrue(first.truncated)
        assertTrue(first.content.first() is InlineNode.Strong)
        assertTrue(first.endCharOffsetExclusive in 60..80)
        assertEquals(500, doc.notes.single().plainText().length)
    }

    @Test fun firstCallOrderAndDeduplicationRemainLocalToEachPage() = runBlocking {
        val doc = book(listOf(call("b"), call("a"), call("b"), text("x".repeat(400)), call("b")),
            listOf(note("a", 12), note("b", 12)))
        val pages = engine.paginate(doc, null, key).last().pages
        assertEquals(listOf(NoteId("b"), NoteId("a")), pages.first().footnotes.map { it.noteId })
        assertEquals(listOf(2, 1), pages.first().footnotes.map { it.ordinal })
        assertEquals(0, pages.first().footnotes.first().source.charOffset)
        assertEquals(403, pages.last().footnotes.single().source.charOffset)
        assertNoOverlaps(pages)
    }

    @Test fun lastReferenceLineMovesForwardBeforeOverflowIsUsed() = runBlocking {
        val doc = book(listOf(call("a"), text("x".repeat(109)), call("b"), text("x".repeat(200))),
            listOf(note("a", 80), note("b", 80)))
        val pages = engine.paginate(doc, null, key).last().pages
        assertEquals(listOf(NoteId("a")), pages.first().footnotes.map { it.noteId })
        val secondCall = doc.noteIndex.byId.getValue(NoteId("b")).references.single()
        assertTrue(pages.pageIndexContaining(secondCall.source) > 0)
        assertTrue(pages.all { it.footnotes.none { note -> note.hiddenNoteCount > 0 } })
        assertNoOverlaps(pages)
    }

    @Test fun pathologicalSameLineCallsUseOneOverflowLinkAndStillAdvance() = runBlocking {
        val notes = (0 until 100).map { note("n$it", 20) }
        val doc = book((0 until 100).map { call("n$it") } + text("tail"), notes)
        val sameLineMeasurer = BlockMeasurer { block, layout ->
            if (block.source.id == BlockId("p")) MeasurementMetrics(
                listOf(MeasuredLine(0, block.source.plainText().length, 0f, 20f)), layout.contentWidth.toFloat())
            else measurer.measure(block, layout)
        }
        val pages = IncrementalPaginationEngine(sameLineMeasurer).paginate(doc, null, key).last().pages
        assertEquals(1, pages.size)
        val footnotes = pages.single().footnotes
        val overflow = footnotes.single { it.hiddenNoteCount > 0 }
        assertEquals(100, footnotes.count { it.hiddenNoteCount == 0 } + overflow.hiddenNoteCount)
        assertEquals(footnotes.last(), overflow)
        assertEquals("${overflow.hiddenNoteCount} more notes", overflow.content.plainText())
        assertNoOverlaps(pages)
    }

    @Test fun missingOrEmptyNotesKeepOriginalPaginationExactly() = runBlocking {
        val content = listOf(text("x".repeat(90)), call("n"), text("y".repeat(500)))
        val absent = book(content, emptyList())
        val empty = book(content, listOf(note("n", 0)))
        assertEquals(engine.paginate(absent, null, key).last().pages, engine.paginate(empty, null, key).last().pages)
    }

    @Test fun noteReflowPreservesSemanticAnchorAndImmutableIncrementalPrefixes() = runBlocking {
        val content = (0 until 60).flatMap { listOf(text("x".repeat(99)), call("n")) }
        val doc = book(content, listOf(note("n", 40)))
        val anchor = BookLocation(BlockId("p"), 1499, 0)
        val snapshots = engine.paginate(doc, anchor, key).toList()
        assertTrue(snapshots.size > 2)
        snapshots.zipWithNext().forEach { (a, b) -> assertEquals(a.pages, b.pages.take(a.pages.size)) }
        for (layout in listOf(key, key.copy(pageMode = PageMode.SPREAD, pageWidthPx = 100),
            key.copy(fontSizeSp = 32f), key.copy(pageHeightPx = 1))) {
            val page = engine.pageContaining(doc, anchor, layout)
            assertTrue(page.blocks.any { anchor.charOffset >= it.startCharOffset && anchor.charOffset < it.endCharOffsetExclusive })
            assertNoOverlaps(listOf(page), layout)
        }
    }

    @Test fun hugePreviewIsMeasuredOnceWithBoundedInputAndNeverRetainsPlatformMeasurement() = runBlocking {
        val doc = book((0 until 30).flatMap { listOf(text("x".repeat(99)), call("n")) }, listOf(note("n", 100_000)))
        var measuredNotes = 0
        var previewLength = 0
        val counting = IncrementalPaginationEngine { block, layout ->
            if (block.source.id.value.startsWith("listai:preview:")) {
                measuredNotes++
                previewLength = maxOf(previewLength, block.source.plainText().length)
            }
            measurer.measure(block, layout)
        }
        val pages = counting.paginate(doc, null, key).last().pages
        assertEquals(1, measuredNotes)
        assertTrue(previewLength <= 4096)
        assertTrue(pages.flatMap { it.footnotes }.all { it.truncated })
    }

    @Test fun cancellationPropagatesWhileMeasuringPreview() = runBlocking {
        val cancelling = IncrementalPaginationEngine { block, layout ->
            if (block.source.id.value.startsWith("listai:preview:")) throw CancellationException("obsolete")
            measurer.measure(block, layout)
        }
        try {
            cancelling.paginate(book(listOf(call("n")), listOf(note("n", 10))), null, key).first()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}

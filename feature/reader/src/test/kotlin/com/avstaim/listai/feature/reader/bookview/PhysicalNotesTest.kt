package com.avstaim.listai.feature.reader.bookview

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Rect
import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhysicalNotesTest {
    private val key = LayoutKey(400, 800, PageMode.SINGLE)
    private val measurer = BlockMeasurer { block, layout ->
        val text = block.source.plainText()
        val columns = (layout.contentWidth / 8).coerceAtLeast(1)
        val starts = if (text.isEmpty()) listOf(0) else (0 until text.length step columns).toList()
        MeasurementMetrics(starts.mapIndexed { index, start ->
            MeasuredLine(start, minOf(start + columns, text.length), index * 22f, (index + 1) * 22f)
        }, layout.contentWidth.toFloat())
    }
    private val engine = IncrementalPaginationEngine(measurer)
    private fun section(source: BookDocument) = PhysicalNotes.create(source, "Notes", { "Note $it" }, "back to text")

    @Test fun `empty notes create neither tail nor generated contents row`() {
        val source = book().copy(notes = listOf(BookNote(NoteId("empty"), emptyList())))
        assertNull(section(source))
        val contents = PhysicalContents(source, "Contents", "Begin text", section(source))
        assertEquals(listOf("Begin text"), contents.entries.map { it.title })
    }

    @Test fun `synthetic heading cannot alias a source contents target`() {
        val original = book()
        val sourceId = BlockId("listai:notes:one:heading")
        val source = original.copy(blocks = listOf(BookBlock.Paragraph(sourceId, listOf(InlineNode.Text("Source")))),
            tableOfContents = listOf(BookTocEntry("A chapter", BookLocation(sourceId, 0, 0))))
        val notes = section(source)!!
        assertNotEquals(sourceId, notes.targets.getValue(NoteId("one")).blockId)
        assertNull(notes.noteAt(source.tableOfContents.single().location))
        assertEquals("A chapter", PhysicalContents(source, "Contents", "Begin text", notes).entries.first().title)
    }

    @Test fun `FB2 title heading is projected once and generic numbered titles do not duplicate labels`() {
        val original = book()
        val body = original.notes.first().blocks.single()
        for (title in listOf("1", "Note1", "NOTE 1", "Сноска 1", "Примечание\u00a01", "An explanation", "Note 1 on typography")) {
            val parsedTitle = BookBlock.Heading(BlockId("parsed-note-title"), 2, listOf(InlineNode.Text(title)))
            val note = BookNote(NoteId("one"), listOf(parsedTitle, body), title)
            val source = original.copy(notes = listOf(note))
            val notes = section(source)!!
            assertFalse(notes.document.blocks.contains(parsedTitle))
            assertSame(body, notes.document.blocks.last())
            assertEquals(1, notes.document.blocks.count { it.plainText() == "Note 1" })
            val meaningful = title == "An explanation" || title == "Note 1 on typography"
            assertEquals(if (meaningful) 1 else 0, notes.document.blocks.count { it.plainText() == title && it.id != notes.targets.getValue(note.id).blockId })
            assertEquals(listOf(parsedTitle, body), note.blocks)
        }
    }

    @Test fun `rich notes keep original blocks and flow continuously with localized stable headings`() = runBlocking {
        val source = book()
        val notes = requireNotNull(section(source))
        assertTrue(notes.document.notes.isEmpty())
        assertEquals(source.images, notes.document.images)
        assertTrue(notes.document.blocks.containsAll(source.notes.flatMap { it.blocks }))
        assertEquals(listOf("Notes", "Note 1", "Note 2", "Note 3"),
            notes.document.blocks.filterIsInstance<BookBlock.Heading>().map { it.plainText() })
        assertEquals(1, notes.document.blocks.count { it.plainText() == "An explanation" })
        assertFalse(notes.document.blocks.any { it.plainText() == "1" })
        val pages = engine.paginate(notes.document, null, key.forNotes()).last()
        assertEquals(1, pages.pages.size)
        assertTrue(pages.pages.all { it.footnotes.isEmpty() })
        val contents = PhysicalContents(source, "Contents", "Begin text", notes)
        assertEquals("Notes", contents.entries.last().title)
        assertEquals(notes.targets.values.first(), contents.entries.last().target)
        val ru = PhysicalNotes.create(source, "Примечания", { "Примечание $it" }, "к тексту")!!
        assertTrue(ru.document.blocks.any { it.plainText() == "Примечание 2" })
    }

    @Test fun `distant note jump waits for source completion then lands after text in either presentation`() = runBlocking {
        val source = book()
        val notes = section(source)!!
        val main = engine.paginate(source.copy(notes = emptyList()), null, key).last()
        val tail = engine.paginate(notes.document, null, key.forNotes()).last()
        val request = ReaderJumpRequest(1, ReadingProgress(main.pages.first().start), NoteId("one"),
            source.noteIndex.byId.getValue(NoteId("one")).references.last().source, notes.targets.getValue(NoteId("one")))
        BookPresentation.entries.forEach { mode ->
            assertNull(resolveReaderJump(request, main.copy(complete = false), null, null, mode, notes, tail))
            val resolved = resolveReaderJump(request, main, null, null, mode, notes, tail)!!
            assertEquals(main.pages.size, (resolved.target as PhysicalBookState.Open).semanticPageIndex)
            val navigator = PhysicalBookNavigator(main.pages.size + tail.pages.size)
            val next = navigator.transition(PhysicalBookState.Open(0), TurnDirection.FORWARD, mode)
            assertNotNull(next)
            assertNotEquals(PhysicalBookState.ClosedBack, next!!.to)
        }
    }

    @Test fun `repeated calls retain independent exact UTF16 origins while progress stays in source`() = runBlocking {
        val source = book()
        val notes = section(source)!!
        val initial = BookLocation(source.blocks.first().id, 1, 1)
        val position = ReaderPosition(initial)
        val calls = source.noteIndex.byId.getValue(NoteId("one")).references
        assertEquals(2, calls.size)
        assertNotEquals(calls.first().source, calls.last().source)
        for ((index, call) in calls.withIndex()) {
            val before = position.progress(source.readingLength())
            val request = ReaderJumpRequest(index.toLong(), before, call.noteId, call.source, notes.targets.getValue(call.noteId))
            assertEquals(initial.takeIf { index == 0 } ?: calls.first().source, position.location)
            position.commitJump(request, notes)
            assertEquals(before, position.progress(source.readingLength()))
            assertEquals(call.source, notes.returnOrigin(call.noteId, position.visit))
            position.commitJump(ReaderJumpRequest(100 + index.toLong(), before.copy(location = call.source),
                returningFromNote = true), notes)
            assertEquals(call.source, position.location)
            assertNull(position.visit)
        }
    }

    @Test fun `long note continuation survives saver and reflow then clears visit outside section`() = runBlocking {
        val source = book(long = true)
        val notes = section(source)!!
        val main = engine.paginate(source.copy(notes = emptyList()), null, key).last()
        val tail = engine.paginate(notes.document, null, key.forNotes()).last()
        assertTrue(tail.pages.size > 3)
        val call = source.noteIndex.byId.getValue(NoteId("one")).references.last()
        val position = ReaderPosition(main.pages.first().start)
        position.commitJump(ReaderJumpRequest(1, position.progress(source.readingLength()), call.noteId,
            call.source, notes.targets.getValue(call.noteId)), notes)
        position.commit(PhysicalBookState.Open(main.pages.size + 2), main, notes = notes, notesSnapshot = tail)
        val origin = position.location
        assertEquals(call.source, position.visit!!.origin)
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(ReaderPosition.Saver) { scope.save(position) }!!
        val restored = ReaderPosition.Saver.restore(saved)!!
        assertEquals(position.noteLocation, restored.noteLocation)
        assertEquals(position.visit, restored.visit)
        val resized = engine.paginate(notes.document, restored.noteLocation, key.forNotes().copy(fontSizeSp = 26f)).last()
        assertEquals(PhysicalBookState.Open(main.pages.size + resized.pages.pageIndexContaining(restored.noteLocation!!)),
            restored.initialState(main, notes = notes, notesSnapshot = resized))
        tail.pages.forEach { page ->
            val link = notes.returns(page, tail.key, position.visit).single()
            assertEquals(call.noteId, link.noteId)
            assertEquals(call.source, notes.returnOrigin(link.noteId, position.visit))
        }
        assertEquals(origin, restored.location)
        restored.commit(PhysicalBookState.Open(0), main, notes = notes, notesSnapshot = tail)
        assertNull(restored.noteLocation)
        assertNull(restored.visit)
        assertEquals(main.pages.first().start, restored.location)
    }

    @Test fun `unreferenced notes have no return without a visit and touch bounds stay on their leaf`() {
        val notes = section(book())!!
        assertNull(notes.returnOrigin(NoteId("unlinked"), null))
        assertNotNull(notes.firstCall(NoteId("one")))
        val leaf = Rect(400f, 0f, 800f, 800f)
        val bounds = noteTapBounds(Rect(0f, 50f, 5f, 55f), leaf, key, 1f)
        assertEquals(leaf.left, bounds.left)
        assertTrue(bounds.height >= 38f)
        assertTrue(bounds.right <= leaf.right)
    }

    @Test fun `return from note continuation keeps exact late call while first notes leaf faces source text`() = runBlocking {
        val source = mixedBoundaryBook()
        val notes = section(source)!!
        val spreadKey = key.copy(pageMode = PageMode.SPREAD)
        val main = engine.paginate(source, null, spreadKey).last()
        val tail = engine.paginate(notes.document, null, spreadKey.forNotes()).last()
        // Place the first notes leaf on the right, opposite the final source page.
        val contents = PhysicalContents(source, "Contents", "Begin reading", notes)
        val toc = engine.paginate(contents.document, null, spreadKey).last().takeIf { main.pages.size % 2 == 1 }
        assertTrue(toc == null || toc.pages.size == 1)
        val notesStart = main.pages.size + (toc?.pages?.size ?: 0)
        assertEquals(0, notesStart % 2)
        assertTrue(tail.pages.size > 3)
        val calls = source.noteIndex.byId.getValue(NoteId("two")).references
        val call = calls.last()
        assertTrue(call.source.charOffset > 4_000)
        assertNotEquals(calls.first().source, call.source)
        val beforeVisit = main.pages[main.pages.pageIndexContaining(call.source)].start
        val position = ReaderPosition(beforeVisit)
        position.commitJump(ReaderJumpRequest(1, position.progress(source.readingLength()), call.noteId,
            call.source, notes.targets.getValue(call.noteId)), notes)
        val visit = position.visit
        position.commit(PhysicalBookState.Open(notesStart + 1), main, contents, toc, notes, tail)
        assertEquals(visit, position.visit)

        val boundary = PhysicalBookState.Open(notesStart - 1)
        assertEquals(listOf(notesStart - 1, notesStart),
            visiblePageIndices(boundary, BookPresentation.SPREAD, notesStart + tail.pages.size))
        position.commit(boundary, main, contents, toc, notes, tail)
        assertEquals("The right leaf still contains the visited notes", visit, position.visit)
        assertEquals(beforeVisit, position.location)
        assertEquals(tail.pages.first().start, position.noteLocation)
        val printedReturn = notes.returns(tail.pages.first(), tail.key, position.visit).single()
        assertEquals(call.source, notes.returnOrigin(printedReturn.noteId, position.visit))

        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(ReaderPosition.Saver) { scope.save(position) }!!
        val restored = ReaderPosition.Saver.restore(saved)!!
        val resizedKey = key.copy(pageWidthPx = 260, fontSizeSp = 26f)
        val resizedMain = engine.paginate(source, beforeVisit, resizedKey).last()
        val resizedTail = engine.paginate(notes.document, restored.noteLocation, resizedKey.forNotes()).last()
        val restoredState = restored.initialState(resizedMain, notes = notes, notesSnapshot = resizedTail)
        assertEquals(PhysicalBookState.Open(resizedMain.pages.size), restoredState)
        assertEquals(visit, restored.visit)
        val origin = notes.returnOrigin(printedReturn.noteId, restored.visit)!!
        val returning = ReaderJumpRequest(2, restored.progress(source.readingLength()).copy(location = origin),
            returningFromNote = true)
        val jump = resolveReaderJump(returning, resizedMain, null, null, BookPresentation.SINGLE, notes, resizedTail)!!
        val actualPage = (jump.target as PhysicalBookState.Open).semanticPageIndex
        assertTrue(resizedMain.pages[actualPage].blocks.any { fragment ->
            fragment.blockId == call.source.blockId && call.source.charOffset in
                fragment.startCharOffset until fragment.endCharOffsetExclusive
        })
        // Frontmatter can put this exact call on a right leaf; a return must not replace its
        // semantic target with the canonical left-hand spread anchor.
        val frontmatterCount = if (actualPage % 2 == 0) 2 else 1
        val spreadJump = resolveReaderJump(returning, resizedMain, null, null, BookPresentation.SPREAD,
            notes, resizedTail, annotationPageCount = frontmatterCount)!!
        val rightLeaf = actualPage + frontmatterCount
        assertEquals(PhysicalBookState.Open(rightLeaf), spreadJump.target)
        assertEquals(listOf(rightLeaf - 1, rightLeaf), visiblePageIndices(spreadJump.target,
            BookPresentation.SPREAD, frontmatterCount + resizedMain.pages.size + resizedTail.pages.size))
        restored.commitJump(returning, notes)
        assertEquals(call.source, restored.location)
        assertNull(restored.visit)

        position.commit(PhysicalBookState.Open(notesStart - 3), main, contents, toc, notes, tail)
        assertNull("Leaving every notes leaf ends the visit", position.visit)
        assertNull(position.noteLocation)
    }

    @Test fun `natural mixed source and notes spread without a visit keeps main reading progress`() = runBlocking {
        val source = mixedBoundaryBook()
        val notes = section(source)!!
        val spreadKey = key.copy(pageMode = PageMode.SPREAD)
        val main = engine.paginate(source, null, spreadKey).last()
        val tail = engine.paginate(notes.document, null, spreadKey.forNotes()).last()
        val contents = PhysicalContents(source, "Contents", "Begin reading", notes)
        val toc = engine.paginate(contents.document, null, spreadKey).last().takeIf { main.pages.size % 2 == 1 }
        val notesStart = main.pages.size + (toc?.pages?.size ?: 0)
        val position = ReaderPosition(main.pages.first().start)
        position.commit(PhysicalBookState.Open(notesStart - 1), main, contents, toc, notes, tail)
        assertEquals(main.pages.last().start, position.location)
        assertNull(position.visit)
        assertNull(position.noteLocation)
        assertEquals(notes.firstCall(NoteId("one")), notes.returnOrigin(NoteId("one"), position.visit))
    }

    @Test fun `several notes share one single line return below content and above page number`() = runBlocking {
        val source = book()
        val notes = section(source)!!
        val call = source.noteIndex.byId.getValue(NoteId("one")).references.last()
        val visit = FootnoteVisit(call.noteId, call.source, notes.targets.getValue(call.noteId))
        for (mode in PageMode.entries) {
            val notesKey = key.copy(pageMode = mode).forNotes()
            assertEquals(key.marginX, notesKey.marginX, 0f)
            val page = engine.paginate(notes.document, null, notesKey).last().pages.single()
            assertTrue(page.blocks.mapNotNull { notes.noteAt(BookLocation(it.blockId, 0, 0)) }.distinct().size > 1)
            val link = notes.returns(page, notesKey, visit).single()
            assertEquals("↩ back to text", link.label)
            assertEquals(call.source, notes.returnOrigin(link.noteId, visit))
            assertTrue(page.blocks.all { it.y + it.height <= link.bounds.top })
            assertTrue(link.bounds.bottom <= notesKey.pageHeightPx - notesKey.marginY - notesKey.footerHeight)
            assertEquals(notesKey.pageWidthPx / 2f, link.bounds.center.x, 0f)
            assertEquals(40f, link.bounds.height, 0f)
            assertEquals(call.noteId, notes.returns(page, notesKey, null).single().noteId)
            val unlinkedPage = page.copy(blocks = page.blocks.filter { it.blockId == BlockId("unlinked-note") })
            assertTrue(notes.returns(unlinkedPage, notesKey, null).isEmpty())
            assertEquals(call.noteId, notes.returns(unlinkedPage, notesKey, visit).single().noteId)
        }
    }

    private fun book(long: Boolean = false): BookDocument {
        val first = NoteId("one")
        val main = BookBlock.Paragraph(BlockId("main"), listOf(InlineNode.Text("😀 before "),
            InlineNode.Link(LinkTarget.Note(first), listOf(InlineNode.Text("1"))), InlineNode.Text(" between calls "),
            InlineNode.Link(LinkTarget.Note(first), listOf(InlineNode.Text("1"))), InlineNode.Text(" after.")))
        val firstBlock = BookBlock.Paragraph(BlockId("first-note"), listOf(InlineNode.Strong(
            listOf(InlineNode.Text(if (long) "Long note. ".repeat(600) else "First note.")))))
        val quotedBlock = BookBlock.Paragraph(BlockId("quoted-note"),
            listOf(InlineNode.Emphasis(listOf(InlineNode.Text("Quoted note.")))))
        val unlinkedBlock = BookBlock.Paragraph(BlockId("unlinked-note"), listOf(InlineNode.Text("No call.")))
        return BookDocument(BookId("notes-fixture"), BookMetadata("Notes fixture"), listOf(main), notes = listOf(
            BookNote(first, listOf(firstBlock), "1"),
            BookNote(NoteId("two"), listOf(BookBlock.Quote(BlockId("quote"), listOf(quotedBlock))), "An explanation"),
            BookNote(NoteId("unlinked"), listOf(unlinkedBlock))))
    }

    private fun mixedBoundaryBook(): BookDocument {
        val note = NoteId("two")
        fun marker() = InlineNode.Link(LinkTarget.Note(note), listOf(InlineNode.Text("2")))
        val main = BookBlock.Paragraph(BlockId("long-source"), listOf(InlineNode.Text("😀 First note "),
            InlineNode.Link(LinkTarget.Note(NoteId("one")), listOf(InlineNode.Text("1"))),
            InlineNode.Text(" Early call "), marker(),
            InlineNode.Text(" Text before the repeated call. ".repeat(160)), marker(),
            InlineNode.Text(" Text after the repeated call. ".repeat(50))))
        return BookDocument(BookId("mixed-notes-boundary"), BookMetadata("Notes boundary"), listOf(main),
            notes = listOf(
                BookNote(NoteId("one"), listOf(BookBlock.Paragraph(BlockId("first-short-note"),
                    listOf(InlineNode.Text("Short first note."))))),
                BookNote(note, listOf(BookBlock.Paragraph(BlockId("second-long-note"),
                    listOf(InlineNode.Text("Long second note continuation. ".repeat(200))))))))
    }
}

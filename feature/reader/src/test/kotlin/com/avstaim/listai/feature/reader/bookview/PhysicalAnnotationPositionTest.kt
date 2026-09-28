package com.avstaim.listai.feature.reader.bookview

import androidx.compose.runtime.saveable.SaverScope
import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.avstaim.listai.reader.rendering.turn.TurnDirection

class PhysicalAnnotationPositionTest {
    private val key = LayoutKey(400, 800, PageMode.SINGLE)
    private val engine = IncrementalPaginationEngine(BlockMeasurer { block, layout ->
        val text = block.source.plainText()
        val columns = (layout.contentWidth / (layout.fontSizeSp * .5f)).toInt().coerceAtLeast(1)
        val starts = if (text.isEmpty()) listOf(0) else (0 until text.length step columns).toList()
        val lineHeight = layout.fontSizeSp * 1.35f
        MeasurementMetrics(starts.mapIndexed { index, start ->
            MeasuredLine(start, minOf(start + columns, text.length), index * lineHeight, (index + 1) * lineHeight)
        }, layout.contentWidth.toFloat())
    })

    @Test fun `native title-only book restores one white projection and can close its back cover`() = runBlocking {
        val title = BookBlock.Heading(BlockId("native-title-only"), 1, listOf(InlineNode.Text("Title 😀")))
        val source = BookDocument(BookId("only-title"), BookMetadata("Title"), listOf(title), titlePageBlockIds = listOf(title.id))
        val matter = requireNotNull(PhysicalAnnotation.create(source))
        val main = engine.paginate(source, null, key).last()
        val front = engine.paginate(matter.document, null, key.forWhitePaperAnnotation()).last()
        assertTrue(main.pages.isEmpty())
        assertEquals(1, front.pages.flatMap { it.blocks }.count { it.blockId == title.id })
        val legacy = BookLocation(title.id, 2, 2)
        val position = ReaderPosition(legacy)
        position.restoreNativeTitle(source.titlePageBlockIds.toSet())
        var state = position.initialState(main, annotationSnapshot = front)
        assertEquals(PhysicalBookState.Open(0), state)
        val target = resolveReaderJump(ReaderJumpRequest(1, position.progress(source.readingLength())), main,
            null, null, BookPresentation.SINGLE, annotationPageCount = front.pages.size, annotationSnapshot = front)
        assertEquals(state, target?.target)
        val navigator = PhysicalBookNavigator(front.pages.size, hasFrontEndpaper = false)
        repeat(2) {
            if (state != PhysicalBookState.ClosedBack) {
                state = requireNotNull(navigator.transition(state, TurnDirection.FORWARD, BookPresentation.SINGLE)).to
                position.commit(state, main, annotationSnapshot = front, titleBlockIds = matter.titleBlockIds)
            }
        }
        assertEquals(PhysicalBookState.ClosedBack, state)
        assertEquals(legacy, position.location)
    }

    @Test fun `annotation saver preserves exact source checkpoint across font reflow and page-mode change`() = runBlocking {
        val source = document()
        val main = engine.paginate(source, null, key).last()
        val annotation = requireNotNull(PhysicalAnnotation.create(source))
        val front = engine.paginate(annotation.document, null, key.forAnnotation()).last()
        assertTrue(front.pages.size > 3)
        val sourceAnchor = main.pages.last().start
        val exactAnnotationAnchor = front.pages[2].start
        val position = ReaderPosition(sourceAnchor, annotationLocation = exactAnnotationAnchor)
        assertEquals(PhysicalBookState.Open(2),
            position.initialState(main, annotationSnapshot = front))
        val mainProgress = position.progress(source.readingLength())
        position.commit(PhysicalBookState.Open(2), main, annotationSnapshot = front, titleBlockIds = annotation.titleBlockIds)
        assertEquals(exactAnnotationAnchor, position.annotationLocation)
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(ReaderPosition.Saver) { requireNotNull(scope.save(position)) }
        val restored = requireNotNull(ReaderPosition.Saver.restore(saved))
        assertEquals(position.annotationLocation, restored.annotationLocation)
        assertEquals(mainProgress.location, restored.progress(source.readingLength()).location)
        assertEquals(mainProgress.fraction, restored.progress(source.readingLength()).fraction)
        val resizedKey = key.copy(pageMode = PageMode.SPREAD, pageWidthPx = 300, fontSizeSp = 26f)
        val resized = engine.paginate(annotation.document, restored.annotationLocation, resizedKey.forAnnotation()).last()
        val resizedMain = engine.paginate(source, sourceAnchor, resizedKey).last()
        assertEquals(PhysicalBookState.Open(resized.pages.pageIndexContaining(restored.annotationLocation!!)),
            restored.initialState(resizedMain, annotationSnapshot = resized))
        assertEquals(sourceAnchor, restored.location)
        restored.commit(PhysicalBookState.Open(0), main, annotationSnapshot = front, titleBlockIds = annotation.titleBlockIds)
        assertEquals(front.pages.first().start, restored.annotationLocation)
        assertEquals(PhysicalBookState.Open(0), restored.initialState(main, annotationSnapshot = front))
        assertEquals(PhysicalBookState.Open(0),
            restored.initialState(resizedMain, annotationSnapshot = resized))
        assertEquals(sourceAnchor, restored.location)
    }

    @Test fun `explicit main navigation clears supplemental anchors`() = runBlocking {
        val source = document()
        val main = engine.paginate(source, null, key).last()
        val annotation = requireNotNull(PhysicalAnnotation.create(source))
        val front = engine.paginate(annotation.document, null, key.forAnnotation()).last()
        val position = ReaderPosition(main.pages.last().start)
        position.commit(PhysicalBookState.FrontBoundary, main, annotationSnapshot = front)
        val destination = main.pages[1].start
        position.commitJump(position.progress(source.readingLength()).copy(location = destination, annotationLocation = null, titleLocation = null))
        assertNull(position.annotationLocation)
        assertEquals(destination, position.location)
        assertEquals(PhysicalBookState.Open(front.annotationPaperCount + 1),
            position.initialState(main, annotationSnapshot = front))
    }

    @Test fun `absent description still has independent white title and no empty endpaper`() = runBlocking {
        val source = document().copy(metadata = BookMetadata("Book"))
        val main = engine.paginate(source, null, key).last()
        val matter = requireNotNull(PhysicalAnnotation.create(source))
        assertFalse(matter.hasDescription)
        val front = engine.paginate(matter.document, null, key.forWhitePaperAnnotation()).last()
        assertEquals(front.pages.size, front.annotationPaperCount)
        val original = main.pages.last().start
        val title = BookLocation(BlockId("listai:title:name"), 2, 2)
        val position = ReaderPosition(original, titleLocation = title)
        assertEquals(PhysicalBookState.Open(0), position.initialState(main, annotationSnapshot = front))
        assertNull(position.annotationLocation)
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(ReaderPosition.Saver) { requireNotNull(scope.save(position)) }
        val restored = requireNotNull(ReaderPosition.Saver.restore(saved))
        assertEquals(title, restored.titleLocation)
        assertEquals(original, restored.location)
        restored.commit(PhysicalBookState.ClosedFront, main, annotationSnapshot = front, titleBlockIds = matter.titleBlockIds)
        assertEquals(original, restored.location)
        assertEquals(PhysicalBookState.ClosedFront, restored.initialState(main, annotationSnapshot = front))
    }

    @Test fun `title and description keep separate exact anchors through jumps and reflow`() = runBlocking {
        val source = document().copy(metadata = BookMetadata("A very long title ".repeat(60), listOf("Author"), annotation = "Description text"))
        val matter = requireNotNull(PhysicalAnnotation.create(source))
        val main = engine.paginate(source, null, key).last()
        val front = engine.paginate(matter.document, null, key.forWhitePaperAnnotation()).last()
        val mainLocation = main.pages.last().start
        val title = BookLocation(BlockId("listai:title:name"), 123, 0)
        val position = ReaderPosition(mainLocation)
        position.commitJump(ReadingProgress(mainLocation, titleLocation = title))
        val before = position.progress(source.readingLength())
        assertNull(before.annotationLocation)
        assertEquals(title, before.titleLocation)
        val resizedKey = key.copy(pageMode = PageMode.SPREAD, pageWidthPx = 220, fontSizeSp = 32f)
        val resized = engine.paginate(matter.document, title, resizedKey.forWhitePaperAnnotation()).last()
        val sourceResized = engine.paginate(source, mainLocation, resizedKey).last()
        assertEquals(PhysicalBookState.Open(resized.pages.pageIndexContaining(title)), position.initialState(sourceResized, annotationSnapshot = resized))
        position.commitJump(before.copy(annotationLocation = front.pages.first().start, titleLocation = null))
        assertNull(position.titleLocation)
        assertEquals(mainLocation, position.location)
        assertEquals(before.fraction, position.progress(source.readingLength()).fraction)
        assertEquals(PhysicalBookState.Open(0), position.initialState(main, annotationSnapshot = front))
        assertEquals(PhysicalBookState.ClosedFront, resolveReaderJump(ReaderJumpRequest(2,
            ReadingProgress(cover = CoverPosition.FRONT)), main, null, null, BookPresentation.SINGLE)?.target)
    }

    @Test fun `contents return remembers note visit across recreation without replacing main progress`() {
        val main = BookLocation(BlockId("body"), 17, 170)
        val target = BookLocation(BlockId("note"), 23, 230)
        val id = NoteId("note")
        val origin = BookLocation(BlockId("body"), 29, 182)
        val position = ReaderPosition(main, noteLocation = target, noteId = id, visit = FootnoteVisit(id, origin, target))
        val request = position.prepareJump(ReaderJumpRequest(1, ReadingProgress(main, tocEntryIndex = 0)))
        position.commitJump(request, null)
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(ReaderPosition.Saver) { requireNotNull(scope.save(position)) }
        val restored = requireNotNull(ReaderPosition.Saver.restore(saved))
        val returning = restored.prepareJump(ReaderJumpRequest(2, ReadingProgress(main), returningFromContents = true))
        assertEquals(id, returning.noteId)
        assertEquals(target, returning.noteTarget)
        assertEquals(origin, returning.origin)
        restored.commitJump(returning, null)
        assertEquals(FootnoteVisit(id, origin, target), restored.visit)
        assertEquals(main, restored.location)
    }

    @Test fun `legacy native title checkpoint restores its projection without advancing main progress`() = runBlocking {
        val title = BookBlock.Heading(BlockId("native-title"), 1, listOf(InlineNode.Text("A native title")))
        val source = document().copy(blocks = listOf(title) + document().blocks, titlePageBlockIds = listOf(title.id))
        val matter = requireNotNull(PhysicalAnnotation.create(source))
        val nativeLocation = BookLocation(title.id, 5, 5)
        val position = ReaderPosition(nativeLocation)
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(ReaderPosition.Saver) { requireNotNull(scope.save(position)) }
        // Also exercise the old Bundle form, before the title fields were appended.
        val restored = requireNotNull(ReaderPosition.Saver.restore((saved as List<*>).take(20)))
        val before = restored.progress(source.readingLength())
        restored.restoreNativeTitle(source.titlePageBlockIds.toSet())
        val front = engine.paginate(matter.document, restored.titleLocation, key.forWhitePaperAnnotation()).last()
        val main = engine.paginate(source, restored.location, key).last()
        assertEquals(nativeLocation, restored.titleLocation)
        assertEquals(before.location, restored.location)
        assertEquals(before.fraction, restored.progress(source.readingLength()).fraction)
        assertEquals(PhysicalBookState.Open(front.pages.pageIndexContaining(nativeLocation)),
            restored.initialState(main, annotationSnapshot = front))
        val mainLocation = main.pages.last().start
        restored.commitJump(ReadingProgress(mainLocation))
        restored.restoreNativeTitle(source.titlePageBlockIds.toSet())
        assertNull(restored.titleLocation)
        assertEquals(mainLocation, restored.location)
    }

    private fun document() = BookDocument(BookId("annotation-state"),
        BookMetadata("Book", annotation = "A description. ".repeat(1200)),
        listOf(BookBlock.Paragraph(BlockId("main"), listOf(InlineNode.Text("Source text. ".repeat(600))))))
}

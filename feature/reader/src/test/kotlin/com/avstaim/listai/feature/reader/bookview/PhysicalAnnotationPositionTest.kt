package com.avstaim.listai.feature.reader.bookview

import androidx.compose.runtime.saveable.SaverScope
import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

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

    @Test fun `annotation saver preserves exact source checkpoint across font reflow and page-mode change`() = runBlocking {
        val source = document()
        val main = engine.paginate(source, null, key).last()
        val annotation = requireNotNull(PhysicalAnnotation.create(source))
        val front = engine.paginate(annotation.document, null, key.forAnnotation()).last()
        assertTrue(front.pages.size > 3)
        val sourceAnchor = main.pages.last().start
        val exactAnnotationAnchor = front.pages[2].start
        val position = ReaderPosition(sourceAnchor, annotationLocation = exactAnnotationAnchor)
        assertEquals(PhysicalBookState.Open(1),
            position.initialState(main, annotationSnapshot = front))
        val mainProgress = position.progress(source.readingLength())
        position.commit(PhysicalBookState.Open(1), main, annotationSnapshot = front)
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
        assertEquals(PhysicalBookState.Open(resized.pages.pageIndexContaining(restored.annotationLocation!!) - 1),
            restored.initialState(resizedMain, annotationSnapshot = resized))
        assertEquals(sourceAnchor, restored.location)
        restored.commit(PhysicalBookState.FrontBoundary, main, annotationSnapshot = front)
        assertEquals(front.pages.first().start, restored.annotationLocation)
        assertEquals(PhysicalBookState.FrontBoundary, restored.initialState(main, annotationSnapshot = front))
        assertEquals(PhysicalBookState.Open(0),
            restored.initialState(resizedMain, annotationSnapshot = resized))
        assertEquals(sourceAnchor, restored.location)
    }

    @Test fun `main navigation clears an annotation copied with the old checkpoint`() = runBlocking {
        val source = document()
        val main = engine.paginate(source, null, key).last()
        val annotation = requireNotNull(PhysicalAnnotation.create(source))
        val front = engine.paginate(annotation.document, null, key.forAnnotation()).last()
        val position = ReaderPosition(main.pages.last().start)
        position.commit(PhysicalBookState.FrontBoundary, main, annotationSnapshot = front)
        val destination = main.pages[1].start
        position.commitJump(position.progress(source.readingLength()).copy(location = destination))
        assertNull(position.annotationLocation)
        assertEquals(destination, position.location)
        assertEquals(PhysicalBookState.Open(front.annotationPaperCount + 1),
            position.initialState(main, annotationSnapshot = front))
    }

    @Test fun `single front endpaper is stable without an fb2 annotation`() = runBlocking {
        val source = document().copy(metadata = BookMetadata("Book"))
        val main = engine.paginate(source, null, key).last()
        val original = main.pages.first().start
        val position = ReaderPosition(original, cover = "front")

        position.commit(PhysicalBookState.FrontBoundary, main, annotationSnapshot = null)

        assertNull(position.annotationLocation)
        assertEquals(original, position.location)
        assertEquals(CoverPosition.OPEN, position.progress(source.readingLength()).cover)
        assertEquals(PhysicalBookState.FrontBoundary,
            position.initialState(main, annotationSnapshot = null))
    }

    private fun document() = BookDocument(BookId("annotation-state"),
        BookMetadata("Book", annotation = "A description. ".repeat(1200)),
        listOf(BookBlock.Paragraph(BlockId("main"), listOf(InlineNode.Text("Source text. ".repeat(600))))))
}

package com.avstaim.listai.feature.reader.bookview

import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*
import com.avstaim.listai.core.designsystem.BookCoverSurface
import com.avstaim.listai.reader.rendering.geometry.PageCurlGeometryEngine
import com.avstaim.listai.reader.rendering.geometry.Point
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhysicalAnnotationTest {
    @Test fun annotationInkStaysOnTheRigidInsideBoardDuringOpeningAndClosing() = runBlocking {
        val layout = calculateBookCanvasLayout(Size(840f, 660f), BookPresentation.SPREAD, 10f, 6f)
        val key = LayoutKey(840, 660, PageMode.SPREAD, pageWidthPx = layout.pageWidth.toInt(),
            pageHeightPx = layout.pageHeight.toInt()).forAnnotation()
        val annotation = requireNotNull(PhysicalAnnotation.create(source()))
        val snapshot = IncrementalPaginationEngine(AndroidBookMeasurer(annotation.document, "Missing"))
            .paginate(annotation.document, null, key).last()
        val ink = AndroidPageRasterizer(annotation.document, key, "Missing").render(snapshot.pages.first()).asBookSurface(0)
        val stock = BookCoverSurface(null, null, IntSize(key.pageWidthPx, key.pageHeightPx))
        val outside = BookVisualSurface.Cover(stock, ExternalBookCoverFace.FRONT)
        val blank = BookVisualSurface.Cover(stock, ExternalBookCoverFace.BACK, isInside = true)
        val printed = blank.copy(insideContent = ink)
        for (direction in TurnDirection.entries) {
            val sourceBounds = layout.sourcePage(direction)
            val destinationBounds = requireNotNull(layout.destinationPage(direction))
            val engine = PageCurlGeometryEngine()
            val start = engine.calculate(sourceBounds.toCurlRect(),
                Point(if (direction == TurnDirection.FORWARD) sourceBounds.right.toDouble() else sourceBounds.left.toDouble(),
                    sourceBounds.bottom.toDouble()), direction, destinationBounds = destinationBounds.toCurlRect())
            for (fraction in if (direction == TurnDirection.FORWARD) listOf(.7f, .9f, 1f) else listOf(0f, .1f, .3f)) {
                val pointer = Point(start.grabPoint.x + (start.completionPointer.x - start.grabPoint.x) * fraction,
                    start.grabPoint.y + (start.completionPointer.y - start.grabPoint.y) * fraction)
                val geometry = engine.calculate(sourceBounds.toCurlRect(), pointer, direction,
                    destinationBounds = destinationBounds.toCurlRect())
                fun draw(inside: BookVisualSurface.Cover): Bitmap {
                    val bitmap = Bitmap.createBitmap(840, 660, Bitmap.Config.ARGB_8888)
                    val frame = CurlFrame(direction, geometry,
                        if (direction == TurnDirection.FORWARD) outside else inside,
                        sourceBounds, if (direction == TurnDirection.FORWARD) PageSide.RIGHT else PageSide.LEFT,
                        if (direction == TurnDirection.FORWARD) inside else outside, destinationBounds)
                    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(AndroidCanvas(bitmap)), Size(840f, 660f)) {
                        drawPhysicalBook(layout, VisibleBookSurfaces(BookVisualSurface.PlainPaper, BookVisualSurface.PlainPaper),
                            0f, PhysicalBookPose.OPEN, frame, stock)
                    }
                    return bitmap
                }
                val unprinted = draw(blank)
                val annotated = draw(printed)
                val projection = calculateRigidCoverProjection(sourceBounds, destinationBounds, direction, geometry.progress.toFloat())
                var inkPixels = 0
                for (y in 0 until annotated.height) for (x in 0 until annotated.width) {
                    if (unprinted.getPixel(x, y) != annotated.getPixel(x, y)) {
                        inkPixels++
                        assertTrue("Ink escaped the moving board at $x,$y", x >= projection.projectedBounds.left - 1 &&
                            x <= projection.projectedBounds.right + 1 && y >= projection.projectedBounds.top - 1 &&
                            y <= projection.projectedBounds.bottom + 1)
                    }
                }
                assertTrue("Annotation must remain visible on the inside board at $direction/$fraction", inkPixels > 20)
                unprinted.recycle()
                annotated.recycle()
            }
        }
    }

    @Test fun annotationPagesRetainAllTextStyleAndNormalTypeSizeWhileMainAnchorsStayUnchanged() = runBlocking {
        for (mode in PageMode.entries) for (dark in listOf(false, true)) {
            val key = LayoutKey(360, 640, mode, fontSizeSp = 22f, darkTheme = dark,
                pageWidthPx = 360, topContentInsetDp = 72f)
            val source = source()
            val annotation = requireNotNull(PhysicalAnnotation.create(source))
            val annotationKey = key.forAnnotation()
            assertEquals(key.fontSizeSp, annotationKey.fontSizeSp)
            assertEquals(key.textScale, annotationKey.textScale)
            val annotationSnapshot = IncrementalPaginationEngine(AndroidBookMeasurer(annotation.document, "Missing"))
                .paginate(annotation.document, null, annotationKey).last()
            assertTrue(annotationSnapshot.pages.size > 2)
            assertEquals(annotationSnapshot.pages.size - 1, annotationSnapshot.annotationPaperCount)
            val flow = annotation.document.readingFlow().associateBy { it.source.id }
            val rasterizer = AndroidPageRasterizer(annotation.document, annotationKey, "Missing")
            val renderedText = StringBuilder()
            annotationSnapshot.pages.forEach { page ->
                val surface = rasterizer.render(page)
                assertNull(surface.footerText)
                assertEquals(dark, surface.darkTheme)
                assertFalse(surface.accessibleText.contains(source.metadata.title))
                page.blocks.forEach { fragment ->
                    assertEquals(1f, fragment.scale)
                    assertTrue(fragment.x >= annotationKey.marginX)
                    assertTrue(fragment.y >= annotationKey.contentTop)
                    renderedText.append(flow.getValue(fragment.blockId).source.plainText()
                        .substring(fragment.startCharOffset, fragment.endCharOffsetExclusive))
                }
            }
            assertEquals(annotation.document.blocks.joinToString("") { it.plainText() }, renderedText.toString())
            val measured = AndroidBookMeasurer(annotation.document, "Missing").measure(flow.values.first(), annotationKey)
                as AndroidTextMeasurement
            val spans = measured.layout.text as Spanned
            assertTrue(spans.getSpans(0, spans.length, StyleSpan::class.java).isNotEmpty())

            val snapshot = IncrementalPaginationEngine(AndroidBookMeasurer(source, "Missing"))
                .paginate(source, null, key).last()
            val contents = PhysicalContents(source, "Contents", "Begin reading")
            val toc = paginatePhysicalContents(contents, key, AndroidContentsMeasurer())
            assertEquals(listOf("Only source chapter"), contents.entries.map { it.title })
            assertEquals(listOf(1), contents.sourcePageNumbers(snapshot).values.toList())
            val sourceLocation = snapshot.pages.last().start
            val position = ReaderPosition(sourceLocation)
            val before = position.progress(source.readingLength())
            position.commit(PhysicalBookState.FrontBoundary, snapshot, contents, toc,
                annotationSnapshot = annotationSnapshot)
            val coverProgress = position.progress(source.readingLength())
            assertEquals(before.location, coverProgress.location)
            assertEquals(before.fraction, coverProgress.fraction)
            assertEquals(annotationSnapshot.pages.first().start, coverProgress.annotationLocation)
            assertEquals(if (mode == PageMode.SINGLE) PhysicalBookState.FrontBoundary else PhysicalBookState.Open(0),
                ReaderPosition(sourceLocation, annotationLocation = coverProgress.annotationLocation)
                    .initialState(snapshot, contents, toc, annotationSnapshot = annotationSnapshot))
            position.commit(PhysicalBookState.Open(1), snapshot, contents, toc,
                annotationSnapshot = annotationSnapshot)
            val overflowProgress = position.progress(source.readingLength())
            assertEquals(before.location, overflowProgress.location)
            assertEquals(annotationSnapshot.pages[2].start, overflowProgress.annotationLocation)
            assertEquals(PhysicalBookState.Open(1), ReaderPosition(sourceLocation,
                annotationLocation = overflowProgress.annotationLocation).initialState(snapshot, contents, toc,
                annotationSnapshot = annotationSnapshot))
            val mainStart = annotationSnapshot.annotationPaperCount + toc.pages.size
            assertEquals(PhysicalBookState.Open(mainStart), resolveReaderJump(ReaderJumpRequest(1,
                ReadingProgress(snapshot.pages.first().start)), snapshot, contents, toc, BookPresentation.SINGLE,
                annotationPageCount = annotationSnapshot.annotationPaperCount)?.target)
            position.commit(PhysicalBookState.Open(mainStart), snapshot, contents, toc,
                annotationSnapshot = annotationSnapshot)
            assertNull(position.progress(source.readingLength()).annotationLocation)
            assertEquals(snapshot.pages.first().start, position.location)
        }
    }

    @Test fun absentAnnotationLeavesAnUnprintedInsideCover() {
        assertNull(PhysicalAnnotation.create(source().copy(metadata = BookMetadata("No annotation"))))
    }

    private fun source(): BookDocument {
        val rich = buildList<BookBlock> {
            add(BookBlock.Paragraph(BlockId("description-first"), listOf(InlineNode.Text("An opening "),
                InlineNode.Emphasis(listOf(InlineNode.Text("emphasized phrase"))), InlineNode.Text(" in the description."))))
            add(BookBlock.Spacer(BlockId("description-space")))
            repeat(12) { index -> add(BookBlock.Paragraph(BlockId("description-$index"),
                listOf(InlineNode.Text("Description paragraph $index. A quiet library opens toward the garden. ".repeat(6))))) }
        }
        val first = BlockId("source-first")
        return BookDocument(BookId("annotated-book"), BookMetadata("Unique book title", annotationBlocks = rich),
            listOf(BookBlock.Paragraph(first, listOf(InlineNode.Text("Source body. ".repeat(600))))),
            tableOfContents = listOf(BookTocEntry("Only source chapter", BookLocation(first, 0, 0))))
    }
}

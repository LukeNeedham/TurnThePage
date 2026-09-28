package com.avstaim.listai.feature.reader.bookview

import android.text.Spanned
import android.text.style.StyleSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhysicalAnnotationTest {
    @Test fun descriptionAndTitleAreWhitePaperInBothModesAndThemesWithOriginalSourceStyles() = runBlocking {
        for (mode in PageMode.entries) for (dark in listOf(false, true)) {
            val key = LayoutKey(360, 640, mode, fontSizeSp = 22f, darkTheme = dark,
                pageWidthPx = 360, topContentInsetDp = 72f)
            val source = source()
            val frontmatter = requireNotNull(PhysicalAnnotation.create(source, "Описание"))
            assertTrue(frontmatter.hasDescription)
            val paperKey = key.forWhitePaperAnnotation()
            val front = IncrementalPaginationEngine(AndroidBookMeasurer(frontmatter.document, "Missing"))
                .paginate(frontmatter.document, null, paperKey).last()
            assertTrue(front.pages.size > 2)
            assertEquals(front.pages.size, front.annotationPaperCount)
            val titlePage = front.pages.indexOfFirst { page -> page.blocks.any { it.blockId in frontmatter.titleBlockIds } }
            assertTrue(titlePage > 0)
            assertTrue(front.pages[titlePage].blocks.first().blockId in frontmatter.titleBlockIds)
            val rasterizer = AndroidPageRasterizer(frontmatter.document, paperKey, "Missing")
            for (page in listOf(front.pages.first(), front.pages[1], front.pages[titlePage])) {
                val surface = rasterizer.render(page)
                assertFalse("Frontmatter remains white on Fold and in dark theme", surface.darkTheme)
                assertNull(surface.footerText)
                assertTrue(surface.byteCount > 0)
            }
            assertTrue(rasterizer.render(front.pages.first()).accessibleText.startsWith("Описание"))
            assertTrue(rasterizer.render(front.pages[titlePage]).accessibleText.contains(source.metadata.title))
            val flow = frontmatter.document.readingFlow().associateBy { it.source.id }
            val collected = front.pages.flatMap { it.blocks }.joinToString("") { fragment ->
                flow.getValue(fragment.blockId).source.plainText().substring(fragment.startCharOffset, fragment.endCharOffsetExclusive)
            }
            assertEquals(frontmatter.document.blocks.joinToString("") { it.plainText() }, collected)
            val rich = flow.getValue(BlockId("description-first"))
            val measured = AndroidBookMeasurer(frontmatter.document, "Missing").measure(rich, paperKey) as AndroidTextMeasurement
            val styled = measured.layout.text as Spanned
            assertTrue(styled.getSpans(0, styled.length, StyleSpan::class.java).isNotEmpty())
            val main = IncrementalPaginationEngine(AndroidBookMeasurer(source, "Missing")).paginate(source, null, key).last()
            val anchor = main.pages.last().start
            val position = ReaderPosition(anchor)
            val before = position.progress(source.readingLength())
            position.commit(PhysicalBookState.Open(titlePage), main, annotationSnapshot = front,
                titleBlockIds = frontmatter.titleBlockIds)
            assertNotNull(position.titleLocation)
            assertNull(position.annotationLocation)
            assertEquals(before.location, position.progress(source.readingLength()).location)
            assertEquals(before.fraction, position.progress(source.readingLength()).fraction)
            assertEquals(PhysicalBookState.Open(titlePage), position.initialState(main, annotationSnapshot = front))
        }
    }

    @Test fun absentDescriptionCreatesOnlyTitleWhilePdfRemainsUnchanged() {
        val titleOnly = requireNotNull(PhysicalAnnotation.create(source().copy(metadata = BookMetadata("No description"))))
        assertFalse(titleOnly.hasDescription)
        assertTrue(titleOnly.document.blocks.all { it.id in titleOnly.titleBlockIds })
        val pdf = BookDocument(BookId("pdf"), BookMetadata("PDF"), listOf(BookBlock.FixedPage(
            BlockId("page"), "/private/document.pdf", "application/pdf", 0, 300, 400)))
        assertNull(PhysicalAnnotation.create(pdf))
    }

    @Test fun chapterDecorationKeepsSourceCoordinatesAndHasPreparedPixelsBeforeCurl() = runBlocking {
        val heading = BookBlock.Heading(BlockId("h"), 1, listOf(InlineNode.Text("Chapter XI. The Pacific")))
        val doc = BookDocument(BookId("chapter"), BookMetadata("Book"), listOf(heading,
            BookBlock.Paragraph(BlockId("body"), listOf(InlineNode.Text("The sea lay quiet. ".repeat(50))))),
            chapters = listOf(BookChapter(listOf(heading.id), 1, "XI", "Chapter XI. ".length)))
        val key = LayoutKey(360, 640, PageMode.SINGLE)
        val measured = AndroidBookMeasurer(doc, "Missing").measure(doc.readingFlow().first(), key) as AndroidTextMeasurement
        assertNotNull(measured.chapterHeading)
        assertTrue(measured.textTop > 0f)
        assertEquals(heading.plainText().length, measured.layout.text.length)
        assertEquals(0, measured.lines.first().start)
        val page = IncrementalPaginationEngine(AndroidBookMeasurer(doc, "Missing")).paginate(doc, null, key).last().pages.first()
        val surface = AndroidPageRasterizer(doc, key, "Missing").render(page)
        var ink = 0
        val endY = (page.blocks.first().y + measured.textTop).toInt().coerceAtMost(surface.bitmap.height)
        for (y in page.blocks.first().y.toInt() until endY) for (x in 0 until surface.bitmap.width) {
            if (surface.bitmap.getPixel(x, y) ushr 24 != 0) ink++
        }
        assertTrue("Number and vector ornament must be in the prepared raster", ink > 20)
        assertTrue("Chapter title has breathing room before the body", measured.trailingSpace > 0f)
    }

    @Test fun longTitleSpendsWhitespaceBeforeOverflowAndKeepsAllTitleAndAuthorText() = runBlocking {
        val key = LayoutKey(320, 640, PageMode.SINGLE, fontSizeSp = 18f, textScale = 1.5f,
            topContentInsetDp = 72f).forWhitePaperAnnotation()
        val short = requireNotNull(PhysicalAnnotation.create(source().copy(metadata = BookMetadata("Short title", listOf("An Author")))))
        val long = requireNotNull(PhysicalAnnotation.create(source().copy(metadata = BookMetadata(
            "A journey through the distant islands and quiet libraries of the northern seas ".repeat(3),
            listOf("First Author", "Second Author")))))
        val shortMeasure = AndroidBookMeasurer(short.document, "Missing").measure(short.document.readingFlow().first(), key) as AndroidTextMeasurement
        val longMeasure = AndroidBookMeasurer(long.document, "Missing").measure(long.document.readingFlow().first(), key) as AndroidTextMeasurement
        assertTrue(shortMeasure.textTop > 0f)
        assertTrue(longMeasure.textTop < shortMeasure.textTop)
        assertEquals(shortMeasure.layout.paint.textSize, longMeasure.layout.paint.textSize, 0f)
        val pages = IncrementalPaginationEngine(AndroidBookMeasurer(long.document, "Missing"))
            .paginate(long.document, null, key).last().pages
        assertTrue(pages.size > 1)
        val blocks = long.document.readingFlow().associateBy { it.source.id }
        val collected = pages.flatMap { it.blocks }.joinToString("") { fragment ->
            blocks.getValue(fragment.blockId).source.plainText().substring(fragment.startCharOffset, fragment.endCharOffsetExclusive)
        }
        assertEquals(long.document.blocks.joinToString("") { it.plainText() }, collected)
        assertTrue(collected.contains("First Author\nSecond Author"))
    }

    private fun source(): BookDocument {
        val rich = buildList<BookBlock> {
            add(BookBlock.Paragraph(BlockId("description-first"), listOf(InlineNode.Text("An opening "),
                InlineNode.Emphasis(listOf(InlineNode.Text("emphasized phrase"))), InlineNode.Text(" in the description."))))
            repeat(4) { index -> add(BookBlock.Paragraph(BlockId("description-$index"),
                listOf(InlineNode.Text("Description paragraph $index. A quiet library opens toward the garden. ".repeat(6))))) }
        }
        return BookDocument(BookId("annotated-book"), BookMetadata("Unique book title", authors = listOf("Author"), annotationBlocks = rich),
            listOf(BookBlock.Paragraph(BlockId("source-first"), listOf(InlineNode.Text("Source body. ".repeat(600))))))
    }
}

package com.avstaim.listai.feature.reader.bookview

import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpilogueLayoutTest {
    @Test fun oversizedSemanticEpilogueTitleRetainsEveryUtf16CharacterAndItsFinalOrnament() = runBlocking {
        val heading = BookBlock.Heading(BlockId("long-epilogue"), 1,
            listOf(InlineNode.Text("Последний свет над далёким берегом 😀 ".repeat(18))))
        val body = BookBlock.Paragraph(BlockId("body"), listOf(InlineNode.Text("Текст эпилога продолжается.")))
        val document = BookDocument(BookId("oversized-epilogue"), BookMetadata("Книга"), listOf(heading, body),
            chapters = listOf(BookChapter(listOf(heading.id), null, kind = BookChapterKind.EPILOGUE)))
        val key = LayoutKey(280, 420, PageMode.SINGLE, textScale = 1.5f)
        val measurer = AndroidBookMeasurer(document, "Missing")
        val measured = measurer.measure(document.readingFlow().first(), key) as AndroidTextMeasurement
        val anchor = BookLocation(heading.id, 123, 123)
        val snapshot = IncrementalPaginationEngine(measurer).paginate(document, anchor, key).last()
        val fragments = snapshot.pages.flatMap { it.blocks }.filter { it.blockId == heading.id }
        assertTrue("Source content longer than a leaf must flow instead of being clipped", fragments.size > 2)
        assertEquals(0, fragments.first().startCharOffset)
        assertEquals(heading.plainText().length, fragments.last().endCharOffsetExclusive)
        fragments.zipWithNext().forEach { (a, b) -> assertEquals(a.endCharOffsetExclusive, b.startCharOffset) }
        assertTrue(snapshot.pages[snapshot.anchorPageIndex].blocks.any {
            it.blockId == heading.id && it.startCharOffset <= 123 && it.endCharOffsetExclusive > 123
        })
        val last = fragments.last()
        val page = snapshot.pages.single { last in it.blocks }
        val surface = AndroidPageRasterizer(document, key, "Missing").render(page)
        val ornamentTop = (last.y + (measured.textTop + measured.layout.height -
            measured.lines[last.firstLine].top) * last.scale).toInt()
        val ornamentBottom = (ornamentTop + requireNotNull(measured.followingDecoration).height * last.scale).toInt()
        var ink = 0
        for (y in ornamentTop until ornamentBottom.coerceAtMost(surface.bitmap.height)) {
            for (x in 0 until surface.bitmap.width) if (surface.bitmap.getPixel(x, y) ushr 24 != 0) ink++
        }
        assertTrue("The ornament remains attached to the last heading fragment", ink > 20)
        assertEquals(body.id, snapshot.pages.last().blocks.last().blockId)
    }

    @Test fun unnumberedOpeningPreparesItsOriginalTitleAndVectorOrnamentWithTextOnTheSameLeaf() = runBlocking {
        val heading = BookBlock.Heading(BlockId("epilogue"), 1, listOf(InlineNode.Text("Эпилог")))
        val title = BookBlock.Heading(BlockId("title"), 1, listOf(InlineNode.Text("Дом для света")))
        val body = BookBlock.Paragraph(BlockId("body"), listOf(InlineNode.Text("Весной Лида вернулась на Тонкий. ".repeat(20))))
        val document = BookDocument(BookId("epilogue-layout"), BookMetadata("Книга", language = "ru"), listOf(heading, title, body),
            chapters = listOf(BookChapter(listOf(heading.id, title.id), null, kind = BookChapterKind.EPILOGUE)))
        for (mode in PageMode.entries) for (dark in listOf(false, true)) for (scale in listOf(1f, 1.5f)) {
            val key = LayoutKey(if (mode == PageMode.SINGLE) 360 else 900, 760, mode,
                textScale = scale, darkTheme = dark, topContentInsetDp = 72f, localeTag = "ru")
            val measurer = AndroidBookMeasurer(document, "Missing")
            val flow = document.readingFlow()
            val measured = measurer.measure(flow.first(), key) as AndroidTextMeasurement
            val subtitle = measurer.measure(flow[1], key) as AndroidTextMeasurement
            assertEquals("The real source label remains available to selection and accessibility", heading.plainText(), measured.layout.text.toString())
            assertEquals(Typeface.BOLD, measured.layout.paint.typeface.style)
            assertEquals(Typeface.BOLD_ITALIC, subtitle.layout.paint.typeface.style)
            assertTrue(measured.layout.paint.textSize > subtitle.layout.paint.textSize)
            assertTrue(measured.textTop > 0f)
            assertNotNull(measured.followingDecoration)
            val pages = IncrementalPaginationEngine(measurer).paginate(document, null, key).last().pages
            assertTrue(pages.all { it.kind == PageKind.TEXT })
            assertEquals(listOf(heading.id, title.id, body.id), pages.first().blocks.map { it.blockId })
            assertEquals(0, pages.first().blocks.first().startCharOffset)
            assertEquals(heading.plainText().length, pages.first().blocks.first().endCharOffsetExclusive)
            val surface = AndroidPageRasterizer(document, key, "Missing").render(pages.first())
            val first = pages.first().blocks.first()
            val ornamentTop = (first.y + measured.textTop + measured.layout.height).toInt()
            val ornamentBottom = (ornamentTop + requireNotNull(measured.followingDecoration).height).toInt()
            var ink = 0
            for (y in ornamentTop until ornamentBottom.coerceAtMost(surface.bitmap.height)) {
                for (x in 0 until surface.bitmap.width) if (surface.bitmap.getPixel(x, y) ushr 24 != 0) ink++
            }
            assertTrue("The vector ornament is already rasterized before a page curl", ink > 20)
            assertTrue(surface.accessibleText.startsWith("Эпилог"))
            assertFalse(surface.accessibleText.contains("Глава"))
        }
    }
}

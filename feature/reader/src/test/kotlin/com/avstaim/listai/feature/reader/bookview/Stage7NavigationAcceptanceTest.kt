package com.avstaim.listai.feature.reader.bookview

import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.ReaderNavigationState
import com.avstaim.listai.reader.pagination.*
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Stage7NavigationAcceptanceTest {
    private val key = LayoutKey(280, 260, PageMode.SINGLE, fontSizeSp = 14f,
        horizontalMarginsDp = 16f, verticalMarginsDp = 16f)
    private val engine = IncrementalPaginationEngine(BlockMeasurer { block, layout ->
        // Deterministic reflow without Android: text width and line height both depend on font.
        val length = block.source.plainText().length
        val columns = (layout.contentWidth / (layout.fontSizeSp * .55f)).toInt().coerceAtLeast(1)
        val lineHeight = layout.fontSizeSp * 1.35f
        val starts = if (length == 0) listOf(0) else (0 until length step columns).toList()
        MeasurementMetrics(starts.mapIndexed { index, start ->
            MeasuredLine(start, (start + columns).coerceAtMost(length), index * lineHeight, (index + 1) * lineHeight)
        }, layout.contentWidth.toFloat())
    })

    @Test
    fun longContentsEntryRestoresItsUtf16AnchorAcrossFontAndSpreadReflow() = runBlocking {
        val source = sourceBook()
        val before = source.readingFlow()
        val longTitle = "Длинное название раздела 😀 — ".repeat(90)
        val withContents = source.copy(tableOfContents = listOf(BookTocEntry("Глава первая", before.first().let {
            BookLocation(it.source.id, 0, it.globalOffset)
        }, listOf(BookTocEntry(longTitle, before[4].let { BookLocation(it.source.id, 17, it.globalOffset + 17) })))) )
        val contents = PhysicalContents(withContents, "Оглавление", "Начало текста")
        val anchor = contents.location(entry = 2, offset = 901)
        val first = engine.paginate(contents.document, anchor, key).last()
        val changed = key.copy(viewportWidthPx = 360, viewportHeightPx = 180,
            pageWidthPx = 180, pageHeightPx = 180, pageMode = PageMode.SPREAD, fontSizeSp = 30f)
        val second = engine.paginate(contents.document, contents.location(2, 901), changed).last()

        assertNotEquals(first.anchorPageIndex, second.anchorPageIndex)
        assertEquals(anchor, contents.location(2, 901))
        assertEquals(2, contents.entryIndex(anchor))
        listOf(first, second).forEach { snapshot ->
            val page = snapshot.pages[snapshot.anchorPageIndex]
            val fragment = page.blocks.single { it.blockId == anchor.blockId &&
                it.startCharOffset <= 901 && it.endCharOffsetExclusive > 901 }
            assertEquals(before[4].let { BookLocation(it.source.id, 17, it.globalOffset + 17) },
                snapshot.contentsLinkAt(fragment.x + fragment.width / 2, fragment.y + fragment.height / 2,
                    snapshot.anchorPageIndex, contents))
        }
        assertEquals(before, withContents.readingFlow())
        assertEquals(source.id, withContents.id)
        assertNotEquals(withContents.id, contents.document.id)
        assertEquals(source.readingLength(), withContents.readingLength())
    }

    @Test
    fun partialIndexDoesNotInventPageNumbersForUnpreparedText() = runBlocking {
        val source = sourceBook()
        val full = engine.paginate(source, null, key).last()
        assertTrue(full.pages.size > 5)
        val partial = full.copy(pages = full.pages.take(2), complete = false)
        val navigation = ReaderNavigationState(source, partial, full.pages.first().start)
        val initial = navigation.current

        val distant = navigation.previewFraction(.95f)
        assertNull(distant.pageNumber)
        assertNull(distant.totalPages)
        assertTrue(distant.location.fallbackGlobalOffset > partial.pages.last().endExclusive.fallbackGlobalOffset)
        assertEquals("Последняя глава", distant.sectionTitle)
        assertEquals(1, navigation.previewPage(1)!!.pageNumber)
        assertNull(navigation.previewPage(3))
        assertNull(navigation.previewPage(0))
        assertEquals(initial, navigation.current)
    }

    @Test
    fun exactAndPercentageNavigationUseOnlyOriginalBookCoordinates() = runBlocking {
        val source = sourceBook()
        val before = source.readingFlow()
        val pages = engine.paginate(source, null, key).last()
        val navigation = ReaderNavigationState(source, pages, pages.pages[2].start)
        val contents = PhysicalContents(source, "Contents", "Begin reading")
        // Building the supplementary book must not change any page/progress lookup in the source.
        assertTrue(contents.document.blocks.isNotEmpty())
        val end = navigation.previewFraction(1f)
        val last = before.last()
        assertEquals(BookLocation(last.source.id, last.source.plainText().length,
            last.globalOffset + last.source.plainText().length), end.location)
        assertEquals(1f, end.fraction)
        assertEquals(pages.pages.size, end.totalPages)
        assertEquals(pages.pages.size, end.pageNumber)
        assertEquals(pages.pages[2].start, navigation.previewPage(3)!!.location)
        assertEquals(before.first().let { BookLocation(it.source.id, 0, 0) }, navigation.previewFraction(0f).location)
        assertEquals(before, source.readingFlow())
    }

    @Test
    fun bookWithoutTocStillHasAPhysicalLinkToTheFirstSourceBlock() {
        val source = sourceBook().copy(tableOfContents = emptyList())
        val contents = PhysicalContents(source, "Оглавление", "Начало текста")
        assertEquals(2, contents.document.blocks.size)
        assertEquals("Начало текста", contents.document.blocks.last().plainText())
        assertEquals(BookLocation(source.readingBlocks().first().id, 0, 0), contents.targets.values.single())
        assertEquals(source.blocks, sourceBook().blocks)
    }

    private fun sourceBook(): BookDocument {
        val blocks = List(12) { index -> BookBlock.Paragraph(BlockId("original-$index"),
            listOf(InlineNode.Text("Страница $index. Белая бумага и история 😀. ".repeat(24)))) }
        val source = BookDocument(BookId("sha256:stage6-source"), BookMetadata("Original book"), blocks)
        val flow = source.readingFlow()
        return source.copy(tableOfContents = listOf(
            BookTocEntry("Первая глава", BookLocation(blocks.first().id, 0, 0)),
            BookTocEntry("Последняя глава", BookLocation(blocks[8].id, 0, flow[8].globalOffset)),
        ))
    }
}

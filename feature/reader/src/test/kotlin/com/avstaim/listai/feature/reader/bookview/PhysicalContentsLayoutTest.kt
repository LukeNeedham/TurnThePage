package com.avstaim.listai.feature.reader.bookview

import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhysicalContentsLayoutTest {
    private val key = LayoutKey(360, 640, PageMode.SINGLE, topContentInsetDp = 72f)
    private val measurer = ContentsTextMeasurer { text, width, style, layout ->
        val lineHeight = layout.fontSizeSp * (if (style == ContentsTextStyle.TITLE) 1.2f else 1f) * 1.3f
        val columns = (width / (layout.fontSizeSp * .55f)).toInt().coerceAtLeast(1)
        val starts = if (text.isEmpty()) listOf(0) else (0 until text.length step columns).toList()
        MeasurementMetrics(starts.mapIndexed { index, start -> MeasuredLine(start,
            (start + columns).coerceAtMost(text.length), index * lineHeight, (index + 1) * lineHeight) }, width.toFloat())
    }
    private val engine = IncrementalPaginationEngine(BlockMeasurer { block, layout ->
        measurer.measure(block.source.plainText(), layout.contentWidth, ContentsTextStyle.CHAPTER, layout)
    })

    @Test fun `printed rows preserve hierarchy and provide full width 48dp touch targets`() {
        val source = book()
        val contents = PhysicalContents(source, "Оглавление", "Начало текста")
        val snapshot = paginatePhysicalContents(contents, key, measurer)
        assertTrue(snapshot.pages.size > 1)
        assertEquals(0, contents.entryIndex(snapshot.pages.first().start))
        val geometry = ContentsGeometry(key)
        assertTrue(geometry.textWidth(1) < geometry.textWidth(0))
        assertTrue(geometry.textWidth(100) > 0)
        snapshot.pages.forEachIndexed { pageIndex, page ->
            val rows = page.blocks.filter { it.blockId in contents.targets }
            rows.forEach { row ->
                assertTrue(row.height >= 48f * key.density)
                assertEquals(key.marginX, row.x)
                assertEquals(key.contentWidth.toFloat(), row.width)
                assertTrue(row.y >= key.contentTop)
                assertTrue(row.y + row.height <= key.contentTop + key.contentHeight + .001f)
                assertEquals(contents.targets[row.blockId], snapshot.contentsLinkAt(
                    row.x + row.width - 1f, row.y + row.height - 1f, pageIndex, contents))
            }
            rows.zipWithNext().forEach { (first, next) -> assertTrue(first.y + first.height <= next.y) }
        }
        assertEquals("Глава IV\nНазвание главы", contents.entries.first().title)
        assertEquals(listOf(0, 1), contents.entries.take(2).map { it.depth })
        assertEquals(source.readingLength(), book().readingLength())
    }

    @Test fun `long titles split without losing UTF16 anchors through font spread and posture reflow`() = runBlocking {
        val source = book().let { original -> original.copy(tableOfContents = listOf(
            BookTocEntry("Длинное название 😀 ".repeat(90), original.tableOfContents.first().location))) }
        val contents = PhysicalContents(source, "Оглавление", "Начало текста")
        val narrow = key.copy(viewportWidthPx = 520, viewportHeightPx = 320, pageMode = PageMode.SPREAD,
            pageWidthPx = 260, pageHeightPx = 320, fontSizeSp = 28f)
        val anchor = contents.location(1, 901)
        val first = paginatePhysicalContents(contents, key, measurer)
        val second = paginatePhysicalContents(contents, narrow, measurer)
        assertNotEquals(first.pages.pageIndexContaining(anchor), second.pages.pageIndexContaining(anchor))
        listOf(first, second).forEach { snapshot ->
            val fragments = snapshot.pages.flatMap { it.blocks }.filter { it.blockId == anchor.blockId }
            assertEquals(0, fragments.first().startCharOffset)
            assertEquals(contents.entries.single().title.length, fragments.last().endCharOffsetExclusive)
            fragments.zipWithNext().forEach { (before, after) ->
                assertEquals(before.endCharOffsetExclusive, after.startCharOffset)
            }
            val position = ReaderPosition(source.tableOfContents.first().location, tocEntryIndex = 1, tocCharOffset = 901)
            val sourceSnapshot = engine.paginate(source, null, snapshot.key).last()
            val expectedPage = snapshot.pages.pageIndexContaining(anchor)
            assertEquals(PhysicalBookState.Open(expectedPage), position.initialState(sourceSnapshot, contents, snapshot))
            val savedSourceAnchor = position.location
            position.commit(PhysicalBookState.Open(expectedPage), sourceSnapshot, contents, snapshot)
            assertEquals(savedSourceAnchor, position.location)
            assertEquals(1, position.tocEntryIndex)
            assertEquals(snapshot.pages[expectedPage].start.charOffset, position.tocCharOffset)
        }
    }

    @Test fun `source page references wait for exact fragments and distinguish page boundaries`() = runBlocking {
        val source = book()
        val full = engine.paginate(source, null, key).last()
        assertTrue(full.pages.size > 4)
        val later = full.pages[3].start
        val boundary = full.pages[2].start
        val distant = source.readingFlow().last().let { BookLocation(it.source.id, 0, it.globalOffset) }
        val withToc = source.copy(tableOfContents = listOf(
            BookTocEntry("First", full.pages.first().start), BookTocEntry("Boundary", boundary),
            BookTocEntry("Later", later), BookTocEntry("Distant", distant)))
        val contents = PhysicalContents(withToc, "Contents", "Begin reading")
        val prefix = full.copy(pages = full.pages.take(2), complete = false)
        val before = contents.sourcePageNumbers(prefix).values.toList()
        assertEquals(listOf(1, null, null, null), before)
        assertEquals(listOf(1, 3, 4, full.pages[full.pages.pageIndexContaining(distant)].displayNumber),
            contents.sourcePageNumbers(full).values.toList())
        assertSame(full.pages.first(), prefix.pages.first())
        assertEquals(before, contents.sourcePageNumbers(prefix).values.toList())
        assertEquals(source.blocks, withToc.blocks)
        assertEquals(source.id, withToc.id)
    }

    @Test fun `resolving later labels preserves layout checkpoints and unrelated surface identities`() = runBlocking {
        val source = book()
        val contents = PhysicalContents(source, "Contents", "Begin reading")
        val toc = paginatePhysicalContents(contents, key, measurer)
        val sourcePages = engine.paginate(source, null, key).last()
        val numbers = contents.sourcePageNumbers(sourcePages)
        val changedRow = toc.pages.last().blocks.first { it.blockId in contents.targets }.blockId
        val unresolved = numbers + (changedRow to null)
        val labelsBefore = toc.pages.map { ContentsPageNumbers.forPage(it, unresolved) }
        val labelsAfter = toc.pages.map { ContentsPageNumbers.forPage(it, numbers) }
        toc.pages.indices.forEach { index ->
            if (toc.pages[index].blocks.any { it.blockId == changedRow }) assertNotEquals(labelsBefore[index], labelsAfter[index])
            else assertEquals(ReaderSurfaceKey(index, labelsBefore[index]), ReaderSurfaceKey(index, labelsAfter[index]))
        }
        assertEquals(toc, paginatePhysicalContents(contents, key, measurer))
        assertEquals(numbers[changedRow], labelsAfter.last().values[changedRow])
        assertNull(labelsBefore.last().values[changedRow])
    }

    @Test fun `rows that fit one leaf stay together and huge text still makes progress on tiny landscape`() {
        val source = book()
        val contents = PhysicalContents(source, "Contents", "Begin reading")
        val normal = paginatePhysicalContents(contents, key, measurer)
        contents.entries.forEach { entry -> assertEquals(1, normal.pages.sumOf { page ->
            page.blocks.count { it.blockId == entry.blockId } }) }
        val tiny = key.copy(pageHeightPx = 130, viewportHeightPx = 130, pageWidthPx = 160,
            viewportWidthPx = 320, pageMode = PageMode.SPREAD, topContentInsetDp = 20f, fontSizeSp = 44f)
        val snapshot = paginatePhysicalContents(contents, tiny, measurer)
        assertTrue(snapshot.pages.isNotEmpty())
        snapshot.pages.flatMap { it.blocks }.forEach {
            assertTrue(it.height.isFinite() && it.height > 0)
            assertTrue(it.y + it.height <= tiny.contentTop + tiny.contentHeight + .001f)
        }
    }

    private fun book(): BookDocument {
        val blocks = List(24) { index -> BookBlock.Paragraph(BlockId("source-$index"),
            listOf(InlineNode.Text("Белая бумага и тихий город. ".repeat(50)))) }
        val source = BookDocument(BookId("source-sha"), BookMetadata("Book"), blocks)
        val flow = source.readingFlow()
        fun location(index: Int) = BookLocation(blocks[index].id, 0, flow[index].globalOffset)
        val entries = listOf(BookTocEntry("Глава IV\nНазвание главы", location(0), listOf(
            BookTocEntry("Небольшой подраздел", location(1))))) + (2 until blocks.size).map {
            BookTocEntry("Chapter $it — A journey through the quiet city", location(it))
        }
        return source.copy(tableOfContents = entries)
    }
}

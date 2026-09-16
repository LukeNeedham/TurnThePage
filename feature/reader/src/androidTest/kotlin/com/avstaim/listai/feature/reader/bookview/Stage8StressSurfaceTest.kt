package com.avstaim.listai.feature.reader.bookview

import android.os.SystemClock
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*
import java.io.File
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded stress evidence, not a hardware-performance gate on a software-GPU emulator. */
@RunWith(AndroidJUnit4::class)
class Stage8StressSurfaceTest {
    @Test fun fiveHundredNotesAndAHugeNoteKeepLightweightIndicesAndBoundedPreviews() = runBlocking {
        val started = SystemClock.elapsedRealtimeNanos()
        val noteCount = 500
        val notes = List(noteCount) { index ->
            val text = if (index == 0) "A very long explanatory note with complete source text. ".repeat(20_000)
                else "Explanation $index. ".repeat(1 + index % 8)
            BookNote(NoteId("stress-$index"), listOf(BookBlock.Paragraph(BlockId("note-$index"),
                listOf(InlineNode.Text(text)))))
        }
        val blocks = List(noteCount) { index -> BookBlock.Paragraph(BlockId("source-$index"), listOf(
            InlineNode.Text("Source paragraph $index has a call "),
            InlineNode.Link(LinkTarget.Note(notes[index].id), listOf(InlineNode.Text("[${index + 1}]"))),
            InlineNode.Text(". This text continues around its own correctly reserved footnote. ".repeat(4)),
        )) }
        val document = BookDocument(BookId("stress-notes"), BookMetadata("Five hundred notes"), blocks, notes = notes)
        val indexed = SystemClock.elapsedRealtimeNanos()
        assertEquals(noteCount, document.noteIndex.entries.size)
        assertEquals(noteCount, document.noteIndex.referencesByBlock.size)
        val reports = mutableListOf("indexMs=${(indexed - started) / 1_000_000.0}")
        for (key in listOf(LayoutKey(600, 960, PageMode.SINGLE),
            LayoutKey(1200, 960, PageMode.SPREAD, darkTheme = true, localeTag = "ru"))) {
            val before = SystemClock.elapsedRealtimeNanos()
            var measuredPreviewChars = 0
            val android = AndroidBookMeasurer(document, "Missing illustration")
            val engine = IncrementalPaginationEngine(BlockMeasurer { block, layout ->
                if (block.source.id.value.startsWith("listai:preview:")) {
                    measuredPreviewChars = maxOf(measuredPreviewChars, block.source.plainText().length)
                }
                android.measure(block, layout)
            })
            val snapshot = engine.paginate(document, null, key).last()
            val paginated = SystemClock.elapsedRealtimeNanos()
            assertTrue(snapshot.complete)
            assertTrue(snapshot.pages.size > 10)
            assertTrue("Even the million-character note has a bounded measurement input", measuredPreviewChars <= 4096)
            val previews = snapshot.pages.flatMap { it.footnotes }
            assertEquals(noteCount, previews.map { it.noteId }.toSet().size)
            assertTrue(previews.first { it.noteId == NoteId("stress-0") }.truncated)
            for (page in snapshot.pages) {
                val footerTop = page.footnotes.minOfOrNull { it.y } ?: continue
                assertTrue(page.blocks.maxOf { it.y + it.height } <= footerTop + .1f)
                assertTrue(page.footnotes.all { it.y + it.height <= key.contentTop + key.contentHeight + .1f })
                assertTrue(page.blocks.all { it.measurement is MeasurementMetrics })
                assertTrue(page.footnotes.all { it.content.plainText().length <= 4096 })
            }
            // Touch distant pages, not just a hot initial window, retaining only one bitmap at a time.
            val rasterizer = AndroidPageRasterizer(document, key, "Missing illustration")
            val samples = snapshot.pages.filterIndexed { index, _ -> index % (snapshot.pages.size / 12).coerceAtLeast(1) == 0 }
            var pixels = 0L
            for (page in samples) {
                val surface = rasterizer.render(page)
                assertEquals(page.footnotes.isNotEmpty(), surface.noteLinks.any { it.kind == NoteLinkKind.FOOTER })
                pixels += surface.bitmap.allocationByteCount
                surface.bitmap.recycle()
            }
            val rendered = SystemClock.elapsedRealtimeNanos()
            reports += "mode=${key.pageMode} pages=${snapshot.pages.size} notes=$noteCount " +
                "paginationMs=${(paginated - before) / 1_000_000.0} " +
                "rasterSamples=${samples.size} rasterMs=${(rendered - paginated) / 1_000_000.0} " +
                "cumulativeRenderedBytes=$pixels maxPreviewChars=$measuredPreviewChars"
        }

        // Exercise the complete million-character note too, rather than just its short preview.
        // One representative layout and four isolated rasters keep this a bounded emulator run.
        val fullStarted = SystemClock.elapsedRealtimeNanos()
        val fullNotes = requireNotNull(PhysicalNotes.create(document, "Notes", { "Note $it" }, "back to text"))
        val fullKey = LayoutKey(600, 960, PageMode.SINGLE).forNotes()
        val fullSnapshot = IncrementalPaginationEngine(AndroidBookMeasurer(fullNotes.document, "Missing illustration"))
            .paginate(fullNotes.document, null, fullKey).last()
        val fullPaginated = SystemClock.elapsedRealtimeNanos()
        val indexedPssKb = Debug.getPss()
        assertTrue(fullSnapshot.complete)
        assertEquals(noteCount, fullNotes.targets.size)
        assertTrue(fullNotes.document.notes.isEmpty())
        assertTrue(fullSnapshot.pages.all { page ->
            page.footnotes.isEmpty() && page.blocks.all { it.measurement is MeasurementMetrics }
        })
        val hugeBlock = notes.first().blocks.single()
        val hugeLength = hugeBlock.plainText().length
        val hugeFragments = fullSnapshot.pages.flatMap { it.blocks }.filter { it.blockId == hugeBlock.id }
        assertTrue("The full note must span many pages", hugeFragments.size > 100)
        assertEquals(0, hugeFragments.first().startCharOffset)
        assertEquals(hugeLength, hugeFragments.last().endCharOffsetExclusive)
        hugeFragments.zipWithNext().forEach { (previous, next) ->
            assertEquals("The complete note loses no source characters", previous.endCharOffsetExclusive, next.startCharOffset)
        }
        val projectedFlow = fullNotes.document.readingFlow().associateBy { it.source.id }
        val hugeGlobalStart = projectedFlow.getValue(hugeBlock.id).globalOffset
        val hugePages = listOf(0, hugeLength / 2, hugeLength - 1).map { offset ->
            fullSnapshot.pages.pageIndexContaining(BookLocation(hugeBlock.id, offset, hugeGlobalStart + offset))
        }
        val sampleIndices = (hugePages + fullSnapshot.pages.lastIndex).distinct()
        assertEquals(4, sampleIndices.size)
        val sourceOrigin = document.noteIndex.entries.first().references.single().source
        val visit = FootnoteVisit(notes.first().id, sourceOrigin, fullNotes.targets.getValue(notes.first().id))
        val fullRasterizer = AndroidPageRasterizer(fullNotes.document, fullKey, "Missing illustration")
        val fullRasterStarted = SystemClock.elapsedRealtimeNanos()
        var maxLiveBitmapBytes = 0L
        var fullRenderedBytes = 0L
        var largestPssKb = indexedPssKb
        for (pageIndex in sampleIndices) {
            val page = fullSnapshot.pages[pageIndex]
            val surface = fullRasterizer.render(page)
            try {
                val returns = fullNotes.returns(page, fullKey, visit = visit)
                val decorated = surface.withNoteReturns(returns)
                    .withFooter(PageReadingFooter(page.displayNumber, fullSnapshot.pages.size, null, fullNotes.title))
                assertSame("Footer and return ink share one source bitmap", surface.bitmap, decorated.bitmap)
                assertEquals(returns, decorated.noteReturns)
                assertEquals(1, returns.size)
                assertTrue(returns.all { it.bounds.width > 0f && it.bounds.height > 0f })
                if (pageIndex in hugePages) {
                    assertTrue("The beginning and continuations retain their printed return", returns.any { it.noteId == notes.first().id })
                    assertEquals(sourceOrigin, fullNotes.returnOrigin(notes.first().id, visit))
                }
                assertTrue(requireNotNull(decorated.footerText).startsWith("Notes · "))
                assertTrue(decorated.noteLinks.isEmpty())
                maxLiveBitmapBytes = maxOf(maxLiveBitmapBytes, surface.byteCount)
                fullRenderedBytes += surface.byteCount
                largestPssKb = maxOf(largestPssKb, Debug.getPss())
                assertTrue(surface.byteCount <= PAGE_CACHE_BYTES)
            } finally {
                // This native test never publishes these surfaces to a scene or GPU.
                surface.bitmap.recycle()
            }
        }
        val fullRendered = SystemClock.elapsedRealtimeNanos()
        assertEquals(sampleIndices.size.toLong(), fullRasterizer.renderCount)
        fullRasterizer.clearMeasurements()
        reports += "section=full-notes mode=${fullKey.pageMode} pages=${fullSnapshot.pages.size} notes=$noteCount " +
            "hugeNoteChars=$hugeLength hugeNotePages=${hugeFragments.size} " +
            "paginationMs=${(fullPaginated - fullStarted) / 1_000_000.0} " +
            "rasterSamples=${sampleIndices.size} sampleIndices=${sampleIndices.joinToString(",")} " +
            "rasterMs=${(fullRendered - fullRasterStarted) / 1_000_000.0} " +
            "renderCount=${fullRasterizer.renderCount} measureCount=${fullRasterizer.measureCount} " +
            "measurementCacheBudgetBytes=$MEASUREMENT_CACHE_BYTES maxRetainedSurfaces=1 " +
            "maxLiveBitmapBytes=$maxLiveBitmapBytes cumulativeRenderedBytes=$fullRenderedBytes " +
            "indexedPssKb=$indexedPssKb observedMaxPssKb=$largestPssKb"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = requireNotNull(context.getExternalFilesDir("stage8")).also { it.mkdirs() }
        File(directory, "stress-report.txt").writeText(reports.joinToString("\n", postfix = "\n"))
    }
}

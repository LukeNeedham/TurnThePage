package com.avstaim.listai.feature.reader.bookview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ParagraphStyle
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SuperscriptSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.geometry.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.floor

@RunWith(AndroidJUnit4::class)
class AndroidFootnotesSurfaceTest {
    private val key = LayoutKey(412, 600, PageMode.SINGLE)
    private val noteId = NoteId("note")
    private fun call(marker: String = "[1]") = InlineNode.Link(LinkTarget.Note(noteId), listOf(InlineNode.Text(marker)))
    private fun document(note: List<InlineNode>, body: List<InlineNode> = listOf(
        InlineNode.Text("A source 😀 "), call(), InlineNode.Text(" and another "), call(), InlineNode.Text("."),
    )) = BookDocument(BookId("notes-raster"), BookMetadata("Notes"), listOf(
        BookBlock.Paragraph(BlockId("source"), body),
    ), notes = listOf(BookNote(noteId, listOf(BookBlock.Paragraph(BlockId("note-body"), note)))))

    @Test fun superscriptsKeepSourceUtf16OffsetsAndHideOnlyWrappingBrackets() {
        val document = document(listOf(InlineNode.Text("Short note.")))
        val source = document.blocks.single()
        for (dark in listOf(false, true)) {
            val measured = AndroidBookMeasurer(document, "Missing").measure(document.readingFlow().single(),
                key.copy(darkTheme = dark)) as AndroidTextMeasurement
            val text = measured.layout.text as Spanned
            assertEquals(source.plainText().replace("[1]", "\u20601\u2060"), text.toString())
            assertEquals(source.plainText().length, text.length)
            assertEquals(2, text.getSpans(0, text.length, SuperscriptSpan::class.java).size)
            assertEquals(4, text.count { it == '\u2060' })
            assertEquals(0, text.getSpans(0, text.length, UnderlineSpan::class.java).size)
            text.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach {
                assertEquals(noteInkColor(dark), it.foregroundColor)
            }
            document.noteIndex.referencesByBlock.getValue(source.id).forEach { reference ->
                assertEquals("\u20601\u2060", text.subSequence(reference.source.charOffset, reference.endCharOffsetExclusive).toString())
                assertEquals("[1]", source.plainText().substring(reference.source.charOffset, reference.endCharOffsetExclusive))
                assertEquals(reference.source.charOffset.toLong(), reference.source.fallbackGlobalOffset)
            }
        }
    }

    @Test fun superscriptCallsStayWithPrecedingWordAndFollowingCommaAcrossReflow() {
        for (locale in listOf("ru", "en")) {
            val prefix = if (locale == "ru") "Имя, написанное карандашом" else "The name written in pencil"
            val suffix = if (locale == "ru") ", возвращало нас к карте. " else ", led us back to the map. "
            val sourceContent = buildList {
                add(InlineNode.Text("[Обычные скобки] 😀 "))
                repeat(3) { add(InlineNode.Text(prefix)); add(call("[2]")); add(InlineNode.Text(suffix)) }
            }
            val document = document(listOf(InlineNode.Text("A note.")), sourceContent)
            val source = document.blocks.single()
            val references = document.noteIndex.referencesByBlock.getValue(source.id)
            for (fontSize in listOf(18f, 28f)) for (width in (160..460 step 11).toList() + listOf(400, 412)) {
                val reflowKey = key.copy(pageWidthPx = width, fontSizeSp = fontSize, localeTag = locale)
                val layout = (AndroidBookMeasurer(document, "Missing")
                    .measure(document.readingFlow().single(), reflowKey) as AndroidTextMeasurement).layout
                assertEquals(source.plainText().length, layout.text.length)
                assertTrue(layout.text.startsWith("[Обычные скобки] 😀 "))
                assertTrue("The fixture must wrap at $width px", layout.lineCount > 1)
                references.forEach { reference ->
                    val lastLetter = reference.source.charOffset - 1
                    val marker = reference.source.charOffset + 1
                    val comma = reference.endCharOffsetExclusive
                    val beforeLine = layout.getLineForOffset(lastLetter)
                    val markerLine = layout.getLineForOffset(marker)
                    val beforeText = layout.text.subSequence(layout.getLineStart(beforeLine), layout.getLineEnd(beforeLine))
                    val markerText = layout.text.subSequence(layout.getLineStart(markerLine), layout.getLineEnd(markerLine))
                    val wordStart = source.plainText().lastIndexOf(' ', lastLetter) + 1
                    val joinedWord = SpannableStringBuilder(layout.text.subSequence(wordStart, comma + 1)).apply {
                        // The paragraph's first-line indent is not part of this inline cluster.
                        getSpans(0, length, ParagraphStyle::class.java).forEach(::removeSpan)
                    }
                    val joinedWidth = android.text.Layout.getDesiredWidth(joinedWord, layout.paint)
                    val message = "Orphan note call for $locale at $width px / $fontSize sp " +
                        "(content=${layout.width}, joinedWord=$joinedWidth): '$beforeText' -> '$markerText'"
                    if (width == 400 || width == 412) {
                        assertTrue("The portrait regression must exercise attachment, not emergency wrapping", joinedWidth <= layout.width)
                    }
                    // Android must use an emergency break when this unbreakable cluster is wider
                    // than the entire line. Offset checks still cover those pathological widths.
                    if (joinedWidth <= layout.width) {
                        assertEquals(message, layout.getLineForOffset(lastLetter), layout.getLineForOffset(marker))
                        assertEquals(message, layout.getLineForOffset(marker), layout.getLineForOffset(comma))
                    }
                    assertEquals("\u20602\u2060", layout.text.subSequence(reference.source.charOffset, comma).toString())
                    assertEquals("[2]", source.plainText().substring(reference.source.charOffset, comma))
                }
            }
        }
    }

    @Test fun richFooterLivesAbovePageNumberAndPreparedLinksKeepEachExactOrigin() = runBlocking {
        val content = listOf(InlineNode.Text("A "), InlineNode.Emphasis(listOf(InlineNode.Text("quiet "))),
            InlineNode.Strong(listOf(InlineNode.Text("printed "))),
            InlineNode.Strikethrough(listOf(InlineNode.Text("old "))), InlineNode.Text("note."))
        val document = document(content)
        val page = IncrementalPaginationEngine(AndroidBookMeasurer(document, "Missing"))
            .paginate(document, null, key).last().pages.first()
        assertEquals(1, page.footnotes.size)
        val entry = page.footnotes.single()
        assertFalse(entry.truncated)
        assertTrue(entry.y > page.blocks.maxOf { it.y + it.height })
        assertTrue(entry.y + entry.height <= key.contentTop + key.contentHeight + .01f)
        val measured = AndroidBookMeasurer(document, "Missing")
            .measure(footnoteFlowBlock(entry.noteId, entry.content), key.footnoteLayoutKey()) as AndroidTextMeasurement
        val text = measured.layout.text as Spanned
        assertEquals(2, text.getSpans(0, text.length, StyleSpan::class.java).size)
        assertEquals(1, text.getSpans(0, text.length, StrikethroughSpan::class.java).size)

        val surface = AndroidPageRasterizer(document, key, "Missing").render(page)
        val inline = surface.noteLinks.filter { it.kind == NoteLinkKind.INLINE }
        val footer = surface.noteLinks.single { it.kind == NoteLinkKind.FOOTER }
        assertEquals(2, inline.size)
        assertEquals(document.noteIndex.byId.getValue(noteId).references.map { it.source }, inline.map { it.origin })
        assertEquals(inline.first().origin, footer.origin)
        assertEquals(key.marginX, footer.bounds.left, .01f)
        assertTrue(footer.bounds.width > key.contentWidth * .9f)
        surface.noteLinks.forEach { assertTrue("No ink inside prepared note region $it", hasInk(surface.bitmap, it.bounds)) }
        assertTrue(surface.accessibleText.contains("A quiet printed old note."))

        val ruleY = (entry.y - 7 * key.density).toInt()
        assertTrue(hasInk(surface.bitmap, Rect(key.marginX, ruleY - 1f, key.marginX + key.contentWidth * .25f, ruleY + 2f)))
        assertFalse(hasInk(surface.bitmap, Rect(key.marginX + key.contentWidth * .30f,
            ruleY - 1f, key.marginX + key.contentWidth, ruleY + 2f)))
    }

    @Test fun longPreviewShowsContinuationAndKeepsFooterAndReturnRegionsThroughWrappers() = runBlocking {
        val document = document(listOf(InlineNode.Text("A long explanatory passage. ".repeat(140))))
        val page = IncrementalPaginationEngine(AndroidBookMeasurer(document, "Missing"))
            .paginate(document, null, key).last().pages.first()
        assertTrue(page.footnotes.single().truncated)
        val rasterizer = AndroidPageRasterizer(document, key, "Missing")
        val surface = rasterizer.render(page)
        assertTrue(surface.accessibleText.endsWith("… →"))
        assertFalse(surface.accessibleText.contains("……"))
        val returns = listOf(PreparedNoteReturn(noteId, Rect(310f, 35f, 395f, 85f), "↩\nback to text"))
        val decorated = surface.withNoteReturns(returns).withFooter(sourcePageFooter(1, 1, 0, 100))
        assertSame(surface.bitmap, decorated.bitmap)
        assertSame(surface.noteLinks, decorated.noteLinks)
        assertEquals(returns, decorated.noteReturns)
        assertSame(decorated, decorated.withNoteReturns(returns))

        val target = Bitmap.createBitmap(824, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.translate(824f, 0f)
        canvas.scale(-1f, 1f)
        repeat(20) { decorated.drawTo(canvas, RectF(0f, 0f, 412f, 600f)) }
        assertTrue(hasInk(target, Rect(824f - 395f, 35f, 824f - 310f, 85f)))
        assertTrue(hasInk(target, Rect(412f, page.footnotes.first().y, 824f, key.contentTop + key.contentHeight)))
        assertFalse(hasInk(target, Rect(0f, 0f, 412f, 600f)))
        assertEquals(1L, rasterizer.renderCount)
    }

    @Test fun unresolvedNoteLeavesReadableSourceAndCreatesNoFalseFooter() = runBlocking {
        val document = document(emptyList()).copy(notes = emptyList())
        val page = IncrementalPaginationEngine(AndroidBookMeasurer(document, "Missing"))
            .paginate(document, null, key).last().pages.single()
        val surface = AndroidPageRasterizer(document, key, "Missing").render(page)
        assertTrue(page.footnotes.isEmpty())
        assertTrue(surface.noteLinks.isEmpty())
        assertEquals(document.blocks.single().plainText(), surface.accessibleText)
        assertTrue(hasInk(surface.bitmap, Rect(0f, 0f, 412f, 600f)))
    }

    @Test fun overflowingLocalizedCountFitsOneLargeTypeLineAndKeepsItsWholeHitRegion() {
        for (locale in listOf("ru", "en")) for (dark in listOf(false, true)) for (scale in listOf(1f, .55f)) {
            val narrow = key.copy(pageWidthPx = 180, pageHeightPx = 320, fontSizeSp = 32f,
                textScale = 1.2f, localeTag = locale, darkTheme = dark)
            val label = if (locale == "ru") "Ещё 100 примечаний" else "100 more notes"
            val content = listOf(InlineNode.Text(label))
            val document = document(listOf(InlineNode.Text("A note.")))
            val measurer = AndroidBookMeasurer(document, "Missing")
            val measured = measurer.measure(footnoteFlowBlock(noteId, content), narrow.footnoteLayoutKey())
                as AndroidTextMeasurement
            assertTrue("The original overflow label must wrap", measured.layout.lineCount > 1)
            val prepared = prepareFootnoteOverflowLine(measured.layout)
            assertEquals(1, prepared.lineCount)
            assertEquals(prepared.text.length, prepared.getLineEnd(0))
            assertTrue(prepared.text.endsWith("… →"))
            assertTrue(android.text.Layout.getDesiredWidth(prepared.text, prepared.paint) <= prepared.width + .01f)
            val height = measured.lines.first().height * scale
            val entry = PageFootnoteLayout(noteId, 1, BookLocation(BlockId("source"), 12, 12),
                narrow.marginX + narrow.footnoteTextIndent,
                narrow.contentTop + narrow.contentHeight - height, measured.width * scale, height,
                content, 0, measured.lines.first().endExclusive, false,
                MeasurementMetrics(measured.lines, measured.width), hiddenNoteCount = 100, scale = scale)
            val bitmap = Bitmap.createBitmap(narrow.pageWidthPx, narrow.pageHeightPx, Bitmap.Config.ARGB_8888)
            val result = rasterizeFootnotes(Canvas(bitmap), listOf(entry), narrow, measurer)
            assertEquals(label, result.accessibleText)
            assertEquals(listOf(PreparedNoteLink(noteId, entry.source,
                Rect(narrow.marginX, entry.y, entry.x + entry.width, entry.y + entry.height), NoteLinkKind.FOOTER)), result.links)
            val arrowOffset = prepared.text.lastIndexOf('→')
            val arrowLeft = entry.x + prepared.getPrimaryHorizontal(arrowOffset) * scale
            assertTrue("The continuation arrow must be printed inside the reserved overflow line",
                hasInk(bitmap, Rect(arrowLeft, entry.y, entry.x + entry.width, entry.y + entry.height)))
            assertFalse("Overflow ink must not enter the ordinary page footer",
                hasInk(bitmap, Rect(0f, ceil(entry.y + entry.height), narrow.pageWidthPx.toFloat(), narrow.pageHeightPx.toFloat())))
        }
    }

    private fun hasInk(bitmap: Bitmap, bounds: Rect): Boolean {
        for (y in floor(bounds.top).toInt().coerceAtLeast(0) until ceil(bounds.bottom).toInt().coerceAtMost(bitmap.height)) {
            for (x in floor(bounds.left).toInt().coerceAtLeast(0) until ceil(bounds.right).toInt().coerceAtMost(bitmap.width)) {
                if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 0) return true
            }
        }
        return false
    }
}

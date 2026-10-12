package com.avstaim.listai.feature.reader.bookview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextDirectionHeuristics
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.SuperscriptSpan
import androidx.compose.ui.geometry.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.designsystem.PlyosTypefaceFamily
import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.floor

/** Native layout, source coordinates and prepared pixels of dialogue in the bundled book face. */
@RunWith(AndroidJUnit4::class)
class DialogueTypographyTest {
    private val typefaces by lazy {
        PlyosTypefaceFamily.load(InstrumentationRegistry.getInstrumentation().targetContext.resources)
    }
    private val continuation = "море у берега тихое, но дальняя лодка уже повернула к острову. "

    @Test fun firstWordKeepsTheSameDistanceFromTheDashOnJustifiedAndFinalLines() = runBlocking(Dispatchers.Default) {
        for (key in layouts()) {
            val short = paragraph("short", "— Да.")
            val long = paragraph("long", "— Да " + continuation.repeat(12))
            val source = document(short, long)
            val finalLine = measured(source, short.id, key)
            val justified = measured(source, long.id, key)
            val message = description(key)
            assertEquals("The reference must be a final, unexpanded line: $message", 1, finalLine.lineCount)
            assertTrue("The dialogue must exercise justified lines: $message", justified.lineCount > 2)
            assertEquals(0, justified.getLineForOffset(2))
            // Layout.getPrimaryHorizontal reports natural advances, without drawn
            // justification. Measure the actual printed dash and first glyph instead.
            assertEquals("The first word must not drift after the dialogue dash: $message",
                firstWordAdvance(finalLine), firstWordAdvance(justified), 1f)
            assertEquals("Dialogue keeps the ordinary body first-line indent: $message",
                (justified.paint.textSize * 1.3f).toInt().toFloat(), justified.getPrimaryHorizontal(0), 1f)
            assertEquals("Short and long dialogue use the same indent: $message",
                finalLine.getPrimaryHorizontal(0), justified.getPrimaryHorizontal(0), 1f)
            assertEquals("Continuation lines return to the body margin: $message", 0f,
                justified.getPrimaryHorizontal(justified.getLineStart(1)), 1f)
            assertSame(typefaces.resolve(Typeface.NORMAL), justified.paint.typeface)
        }
        // Less common dialogue punctuation and already non-breaking imported spaces
        // exercise the same visible contract without multiplying the full layout matrix.
        val key = layout(PageMode.SINGLE, 32f, false)
        for (prefix in listOf("— ", "– ", "― ", "- ", "—\u00a0")) {
            val short = paragraph("short-prefix", prefix + "Да.")
            val long = paragraph("long-prefix", prefix + "Да " + continuation.repeat(12))
            val source = document(short, long)
            val finalLine = measured(source, short.id, key)
            val justified = measured(source, long.id, key)
            assertEquals("Every supported dialogue prefix keeps the first word beside its dash: '$prefix'",
                firstWordAdvance(finalLine), firstWordAdvance(justified), 1f)
            assertEquals(short.plainText().length, finalLine.text.length)
            assertEquals(long.plainText().length, justified.text.length)
            assertEquals(prefix + "Да.", short.plainText())
        }
        val short = paragraph("raw-short", "— Да.")
        val long = paragraph("raw-long", "— Да " + continuation.repeat(12))
        assertTrue("The untreated source reproduces the reported expanding dialogue gap",
            firstWordAdvance(rawLayout(long, key)) > firstWordAdvance(rawLayout(short, key)) + 2f)
    }

    @Test fun dialogueStillJustifiesOtherWordGapsAndLeavesOtherParagraphRolesAlone() = runBlocking(Dispatchers.Default) {
        var comparedContinuationLines = 0
        for (key in layouts()) {
            val dialogue = paragraph("dialogue", "— Да " + continuation.repeat(12))
            val layout = measured(document(dialogue), dialogue.id, key)
            val untreated = rawLayout(dialogue, key)
            var expandedBodyLine = false
            for (line in 0 until minOf(5, layout.lineCount - 1)) {
                val start = layout.getLineStart(line)
                val end = lastInkOffset(layout, line)
                val lineText = SpannableStringBuilder(layout.text.subSequence(start, end)).apply {
                    getSpans(0, length, LeadingMarginSpan::class.java).forEach(::removeSpan)
                }
                val naturalWidth = Layout.getDesiredWidth(lineText, layout.paint)
                val ink = lineInk(layout, line)
                val left = ink.indexOfFirst { it }
                val right = ink.indexOfLast { it } + 1
                // Native engines can leave a trailing advance at the right margin.
                // Compare unchanged continuation lines with the untreated layout,
                // then require actual expansion above the natural word advances.
                if (line > 0 && line < untreated.lineCount && lineText.toString() ==
                    untreated.text.subSequence(untreated.getLineStart(line), lastInkOffset(untreated, line)).toString()) {
                    val untreatedInk = lineInk(untreated, line)
                    assertEquals("Unchanged body lines keep their printed left margin: ${description(key)} / $line",
                        untreatedInk.indexOfFirst { it }, left)
                    assertEquals("Unchanged body lines keep their justified right margin: ${description(key)} / $line",
                        untreatedInk.indexOfLast { it } + 1, right)
                    comparedContinuationLines++
                }
                if (right - left > naturalWidth + 2f) expandedBodyLine = true
            }
            assertTrue("Protecting the dialogue gap must preserve inter-word justification: ${description(key)}",
                expandedBodyLine)
        }
        assertTrue("The matrix must compare unchanged continuation lines with their original justification",
            comparedContinuationLines > 0)

        for (dark in listOf(false, true)) {
            val key = layout(PageMode.SINGLE, 18f, dark)
            val prose = paragraph("prose", "Обычный текст — это пауза, а не начало реплики. " + continuation)
            val quoted = paragraph("quoted", "— Да " + continuation)
            val verse = paragraph("verse", "— Да " + continuation, ParagraphRole.VERSE)
            val author = paragraph("author", "— Да, автор", ParagraphRole.TEXT_AUTHOR)
            val subtitle = paragraph("subtitle", "— Да, подзаголовок", ParagraphRole.SUBTITLE)
            val source = document(prose, BookBlock.Quote(BlockId("quote"), listOf(quoted)), verse, author, subtitle)
            for (block in listOf(prose, quoted, verse, author, subtitle)) {
                assertEquals("Non-dialogue prose and non-justified roles keep their original display text",
                    block.plainText(), measured(source, block.id, key).text.toString())
            }
            assertEquals(Layout.Alignment.ALIGN_NORMAL, measured(source, quoted.id, key).alignment)
            assertEquals(Layout.Alignment.ALIGN_OPPOSITE, measured(source, author.id, key).alignment)
            assertEquals(Layout.Alignment.ALIGN_CENTER, measured(source, subtitle.id, key).alignment)
        }
    }

    @Test fun styledFirstWordAndPreparedNoteLinksKeepTheirOriginalUtf16Origins() = runBlocking(Dispatchers.Default) {
        val prefix = paragraph("before-dialogue", "До разговора 😀.")
        val noteId = NoteId("dialogue-note")
        val dialogue = BookBlock.Paragraph(BlockId("rich-dialogue"), listOf(
            InlineNode.Text("—"), InlineNode.Emphasis(listOf(InlineNode.Text(" "))),
            InlineNode.Strong(listOf(InlineNode.Emphasis(listOf(InlineNode.Text("Да"))))),
            InlineNode.Text(" говорит"),
            InlineNode.Link(LinkTarget.Note(noteId), listOf(InlineNode.Text("[7]"))),
            InlineNode.Text(", " + continuation.repeat(8)),
        ))
        val original = dialogue.plainText()
        val firstWord = BookLocation(dialogue.id, 2, prefix.plainText().length + 3L)
        val source = document(prefix, dialogue).copy(
            notes = listOf(BookNote(noteId, listOf(paragraph("note-text", "Короткое настоящее примечание.")))),
            locationsBySourceId = mapOf("first-word" to firstWord),
        )
        val callStart = original.indexOf("[7]")
        val expectedOrigin = BookLocation(dialogue.id, callStart, prefix.plainText().length + 1L + callStart)
        for (mode in PageMode.entries) for (dark in listOf(false, true)) {
            val key = layout(mode, 32f, dark)
            val layout = measured(source, dialogue.id, key)
            val text = layout.text as Spanned
            assertEquals(original.length, text.length)
            assertEquals("Да", text.subSequence(2, 4).toString())
            val gapStyle = text.getSpans(1, 2, StyleSpan::class.java).single()
            assertEquals("Even an exclusive span covering only the gap survives the display copy", 1, text.getSpanStart(gapStyle))
            assertEquals(2, text.getSpanEnd(gapStyle))
            val styles = text.getSpans(2, 4, StyleSpan::class.java)
            assertEquals(2, styles.size)
            val styledPaint = TextPaint(layout.paint)
            styles.forEach {
                assertEquals(2, text.getSpanStart(it))
                assertEquals(4, text.getSpanEnd(it))
                it.updateMeasureState(styledPaint)
            }
            assertSame(typefaces.resolve(Typeface.BOLD_ITALIC), styledPaint.typeface)
            val superscript = text.getSpans(callStart, callStart + 3, SuperscriptSpan::class.java).single()
            assertEquals(callStart, text.getSpanStart(superscript))
            assertEquals(callStart + 3, text.getSpanEnd(superscript))
            val reference = source.noteIndex.referencesByBlock.getValue(dialogue.id).single()
            assertEquals(expectedOrigin, reference.source)
            assertEquals(callStart + 3, reference.endCharOffsetExclusive)
            assertEquals("[7]", reference.marker)

            val pages = engine(source).paginate(source, firstWord, key).last().pages
            val page = pages.first { candidate -> candidate.blocks.any {
                it.blockId == dialogue.id && callStart >= it.startCharOffset && callStart < it.endCharOffsetExclusive
            } }
            val surface = AndroidPageRasterizer(source, key, "Missing", typefaces = typefaces).render(page)
            val links = surface.noteLinks.filter { it.kind == NoteLinkKind.INLINE }
            assertTrue("A real note call still has a printed tap region", links.isNotEmpty())
            links.forEach {
                assertEquals(noteId, it.noteId)
                assertEquals(expectedOrigin, it.origin)
                assertTrue("The note target covers its printed ink", hasInk(surface.bitmap, it.bounds))
            }
            assertTrue("Accessibility keeps the original note marker", surface.accessibleText.contains("[7]"))
            assertEquals(firstWord, source.locationsBySourceId.getValue("first-word"))
            assertEquals(original, dialogue.plainText())
        }
    }

    @Test fun narrowReflowRetainsTheExactAnchorAndEveryOriginalSourceCharacterInItsRasters() = runBlocking(Dispatchers.Default) {
        val dialogue = paragraph("long-dialogue", "— Да, это точное место 😀. " + continuation.repeat(90))
        val original = dialogue.plainText()
        val offset = original.indexOf("берега", original.length / 2) + 3
        assertTrue(offset > original.length / 2)
        val anchor = BookLocation(dialogue.id, offset, offset.toLong())
        val source = document(dialogue).copy(locationsBySourceId = mapOf("saved-dialogue" to anchor))
        for (dark in listOf(false, true)) {
            val old = engine(source).paginate(source, anchor, layout(PageMode.SINGLE, 18f, dark)).last()
            val position = ReaderPosition(anchor, "open")
            assertEquals(PhysicalBookState.Open(old.anchorPageIndex), position.initialState(old))
            val keys = listOf(
                LayoutKey(240, 430, PageMode.SINGLE, fontSizeSp = 18f, localeTag = "ru", darkTheme = dark),
                LayoutKey(480, 430, PageMode.SPREAD, fontSizeSp = 32f, localeTag = "ru", darkTheme = dark),
                LayoutKey(520, 430, PageMode.SPREAD, fontSizeSp = 12f, localeTag = "ru", darkTheme = dark),
            )
            for (key in keys) {
                val snapshot = engine(source).paginate(source, anchor, key).last()
                assertTrue(snapshot.complete)
                assertTrue(snapshot.pages.size > 1)
                assertEquals(PhysicalBookState.Open(snapshot.anchorPageIndex), position.initialState(snapshot))
                assertEquals("Reflow must not round the saved offset to the new page start", anchor, position.location)
                assertEquals(anchor, position.progress(source.readingLength()).location)
                assertTrue(snapshot.pages[snapshot.anchorPageIndex].blocks.any {
                    it.blockId == anchor.blockId && anchor.charOffset >= it.startCharOffset &&
                        anchor.charOffset < it.endCharOffsetExclusive
                })

                val fragments = snapshot.pages.flatMap { it.blocks }
                var nextOffset = 0
                val rasterizer = AndroidPageRasterizer(source, key, "Missing", typefaces = typefaces)
                val accessible = StringBuilder()
                for (page in snapshot.pages) {
                    val fragment = page.blocks.single()
                    assertEquals(nextOffset, fragment.startCharOffset)
                    assertTrue(fragment.measurement is MeasurementMetrics)
                    assertFalse(Character.isLowSurrogate(original[fragment.startCharOffset]))
                    assertTrue(fragment.y + fragment.height <= key.contentTop + key.contentHeight + .01f)
                    val surface = rasterizer.render(page)
                    val expectedText = original.substring(fragment.startCharOffset, fragment.endCharOffsetExclusive)
                    assertEquals("Raster accessibility uses source text, not display substitutions", expectedText,
                        surface.accessibleText)
                    assertTrue(hasInk(surface.bitmap, Rect(0f, 0f, surface.bitmap.width.toFloat(), surface.bitmap.height.toFloat())))
                    accessible.append(surface.accessibleText)
                    nextOffset = fragment.endCharOffsetExclusive
                }
                assertEquals(original.length, nextOffset)
                assertEquals(original, fragments.joinToString("") {
                    original.substring(it.startCharOffset, it.endCharOffsetExclusive)
                })
                assertEquals(original, accessible.toString())
                assertEquals(anchor, source.locationsBySourceId.getValue("saved-dialogue"))
                assertEquals(original, dialogue.plainText())
            }
        }
    }

    private fun firstWordAdvance(layout: StaticLayout): Float {
        val ink = lineInk(layout, 0)
        val starts = ink.indices.filter { ink[it] && (it == 0 || !ink[it - 1]) }
        assertTrue("Both the opening dash and first word must be printed on the first line", starts.size >= 2)
        return (starts[1] - starts[0]).toFloat()
    }

    private fun lineInk(layout: StaticLayout, line: Int): BooleanArray {
        val top = layout.getLineTop(line)
        val height = layout.getLineBottom(line) - top
        val bitmap = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            layout.draw(canvas)
            val pixels = IntArray(layout.width * height)
            bitmap.getPixels(pixels, 0, layout.width, 0, top, layout.width, height)
            return BooleanArray(layout.width) { x -> (0 until height).any { y -> pixels[y * layout.width + x] ushr 24 > 32 } }
        } finally { bitmap.recycle() }
    }

    private fun rawLayout(block: BookBlock.Paragraph, key: LayoutKey): StaticLayout {
        val text = block.content.toSpannedText(typefaces = typefaces)
        val paint = textPaint(key, typefaces = typefaces)
        text.setSpan(LeadingMarginSpan.Standard((paint.textSize * 1.3f).toInt(), 0),
            0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, key.contentWidth)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(true).setLineSpacing(0f, key.lineHeightMultiplier)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD).build()
    }

    private fun lastInkOffset(layout: StaticLayout, line: Int): Int {
        var end = layout.getLineEnd(line)
        while (end > layout.getLineStart(line) && layout.text[end - 1].isWhitespace()) end--
        return end
    }

    private fun layouts() = PageMode.entries.flatMap { mode ->
        listOf(12f, 18f, 32f).flatMap { size -> listOf(false, true).map { layout(mode, size, it) } }
    }

    private fun layout(mode: PageMode, size: Float, dark: Boolean) = LayoutKey(
        if (mode == PageMode.SINGLE) 320 else 840, 600, mode,
        fontSizeSp = size, localeTag = "ru", darkTheme = dark, hyphenationMode = HyphenationMode.NONE,
    )

    private fun description(key: LayoutKey) = "${key.pageMode}, ${key.contentWidth}px, ${key.fontSizeSp}sp, dark=${key.darkTheme}"

    private fun paragraph(id: String, text: String, role: ParagraphRole = ParagraphRole.BODY) =
        BookBlock.Paragraph(BlockId(id), listOf(InlineNode.Text(text)), role)

    private fun document(vararg blocks: BookBlock) = BookDocument(BookId("native-dialogue"),
        BookMetadata("Реплики", language = "ru"), blocks.toList())

    private fun measured(document: BookDocument, blockId: BlockId, key: LayoutKey) =
        (AndroidBookMeasurer(document, "Missing", typefaces = typefaces)
            .measure(document.readingFlow().single { it.source.id == blockId }, key) as AndroidTextMeasurement).layout

    private fun engine(document: BookDocument) = IncrementalPaginationEngine(
        AndroidBookMeasurer(document, "Missing", typefaces = typefaces))

    private fun hasInk(bitmap: Bitmap, bounds: Rect): Boolean {
        val left = floor(bounds.left).toInt().coerceIn(0, bitmap.width)
        val top = floor(bounds.top).toInt().coerceIn(0, bitmap.height)
        val right = ceil(bounds.right).toInt().coerceIn(left, bitmap.width)
        val bottom = ceil(bounds.bottom).toInt().coerceIn(top, bitmap.height)
        for (y in top until bottom) for (x in left until right) if (bitmap.getPixel(x, y) != 0) return true
        return false
    }
}

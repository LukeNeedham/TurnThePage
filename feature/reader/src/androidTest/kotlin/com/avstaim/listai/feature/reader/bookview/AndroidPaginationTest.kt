package com.avstaim.listai.feature.reader.bookview

import android.graphics.Bitmap
import android.graphics.Canvas
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
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class AndroidPaginationTest {
    private val key = LayoutKey(412, 600, PageMode.SINGLE, localeTag = "ru")
    private fun doc(vararg blocks: BookBlock) = BookDocument(BookId("android"), BookMetadata("Книга", language = "ru"), blocks.toList())

    @Test fun realAndroidLinesCoverCyrillicEmojiAndStyledTextWithoutGaps() = runBlocking {
        val source = BookBlock.Paragraph(BlockId("p"), listOf(
            InlineNode.Text("Начало. "), InlineNode.Strong(listOf(InlineNode.Emphasis(listOf(InlineNode.Text("Жирный курсив. "))))),
            InlineNode.Text("Дорога через лес 🌲 ведёт к реке.\n".repeat(100))))
        val document = doc(source)
        val pages = IncrementalPaginationEngine(AndroidBookMeasurer(document, "Нет изображения"))
            .paginate(document, null, key).last().pages
        val fragments = pages.flatMap { it.blocks }
        assertTrue(pages.size > 3)
        assertEquals(source.plainText(), fragments.joinToString("") {
            source.plainText().substring(it.startCharOffset, it.endCharOffsetExclusive)
        })
        val measurement = fragments.first().measurement as AndroidTextMeasurement
        val spans = measurement.layout.text as Spanned
        assertEquals(2, spans.getSpans(0, spans.length, StyleSpan::class.java).size)
        fragments.forEach {
            assertFalse(it.startCharOffset > 0 && Character.isLowSurrogate(source.plainText()[it.startCharOffset]))
            assertTrue(it.y + it.height <= key.marginY + key.contentHeight + .001f)
        }
        // The exact measured object is reused across fragments and can draw without remeasurement.
        assertTrue(fragments.all { it.measurement === measurement })
        val bitmap = Bitmap.createBitmap(key.pageWidthPx, key.pageHeightPx, Bitmap.Config.ARGB_8888)
        measurement.layout.draw(Canvas(bitmap))
        assertTrue((0 until bitmap.height).any { y -> (0 until bitmap.width).any { x -> bitmap.getPixel(x, y) != 0 } })
    }

    @Test fun imageIsDecodedLazilyDownsampledAndFitsWithoutCropping() = runBlocking {
        val bitmap = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF447766.toInt()) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        var loads = 0
        val source = BookBlock.Image(BlockId("img"), ImageId("img"), "Иллюстрация")
        val document = doc(source).copy(images = listOf(BookImage(ImageId("img"), "image/png", BookBinaryContent { loads++; bytes })))
        assertEquals(0, loads)
        val page = IncrementalPaginationEngine(AndroidBookMeasurer(document, "Нет изображения"))
            .paginate(document, null, key).last().pages.single()
        assertEquals(1, loads)
        val measured = page.blocks.single().measurement as AndroidImageMeasurement
        assertNotNull(measured.bitmap)
        assertTrue(requireNotNull(measured.bitmap).width < 1200)
        assertTrue(measured.width <= key.contentWidth && measured.height <= key.contentHeight)
        assertEquals(2f / 3f, measured.width / measured.height, .001f)
    }

    @Test fun damagedImageHasBoundedPlaceholderAndFollowingTextSurvives() = runBlocking {
        val source = BookBlock.Image(BlockId("img"), ImageId("missing"), "Недоступная иллюстрация")
        val following = BookBlock.Paragraph(BlockId("p"), listOf(InlineNode.Text("Текст продолжается.")))
        val document = doc(source, following)
        val pages = IncrementalPaginationEngine(AndroidBookMeasurer(document, "Нет изображения"))
            .paginate(document, null, key).last().pages
        val measured = pages.first().blocks.first().measurement as AndroidImageMeasurement
        assertNull(measured.bitmap)
        assertNotNull(measured.placeholder)
        assertTrue(pages.flatMap { it.blocks }.any { it.blockId == following.id })
    }

    @Test fun reflowUsesSourceAnchorAndCoverPoseInsteadOfObsoletePageIndex() = runBlocking {
        val source = BookBlock.Paragraph(BlockId("p"), listOf(InlineNode.Text("Тихий город просыпается. ".repeat(300))))
        val document = doc(source)
        val engine = IncrementalPaginationEngine(AndroidBookMeasurer(document, "Нет изображения"))
        val old = engine.paginate(document, null, key).last()
        val position = ReaderPosition()
        position.commit(PhysicalBookState.Open(3), old)
        val anchor = position.location
        val resized = engine.paginate(document, anchor, key.copy(pageWidthPx = 290, pageMode = PageMode.SPREAD)).last()
        assertEquals(PhysicalBookState.Open(resized.anchorPageIndex), position.initialState(resized))
        assertEquals(anchor, position.location)
        position.commit(PhysicalBookState.ClosedBack, old)
        assertEquals(PhysicalBookState.ClosedBack, position.initialState(resized))
    }
}

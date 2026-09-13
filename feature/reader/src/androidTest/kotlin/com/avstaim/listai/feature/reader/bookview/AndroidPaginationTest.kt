package com.avstaim.listai.feature.reader.bookview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*
import com.avstaim.listai.reader.rendering.cache.SurfaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        val measurement = AndroidBookMeasurer(document, "Нет изображения")
            .measure(document.readingFlow().first(), key) as AndroidTextMeasurement
        val spans = measurement.layout.text as Spanned
        assertEquals(2, spans.getSpans(0, spans.length, StyleSpan::class.java).size)
        fragments.forEach {
            assertFalse(it.startCharOffset > 0 && Character.isLowSurrogate(source.plainText()[it.startCharOffset]))
            assertTrue(it.y + it.height <= key.marginY + key.contentHeight + .001f)
        }
        // All fragments share numeric metrics, never a retained StaticLayout or decoded image.
        assertTrue(fragments.all { it.measurement is MeasurementMetrics })
        assertTrue(fragments.all { it.measurement === fragments.first().measurement })
        val bitmap = AndroidPageRasterizer(document, key, "Нет изображения").render(pages.first()).bitmap
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
        assertTrue(page.blocks.single().measurement is MeasurementMetrics)
        val boundsOnly = AndroidBookMeasurer(document, "Нет изображения")
            .measure(document.readingFlow().first(), key) as AndroidImageMeasurement
        assertNull(boundsOnly.bitmap)
        val measured = AndroidBookMeasurer(document, "Нет изображения", decodeImages = true)
            .measure(document.readingFlow().first(), key) as AndroidImageMeasurement
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
        assertTrue(pages.first().blocks.first().measurement is MeasurementMetrics)
        val measured = AndroidBookMeasurer(document, "Нет изображения", decodeImages = true)
            .measure(document.readingFlow().first(), key) as AndroidImageMeasurement
        assertNull(measured.bitmap)
        assertNotNull(measured.placeholder)
        assertTrue(pages.flatMap { it.blocks }.any { it.blockId == following.id })
        val surface = AndroidPageRasterizer(document, key, "Нет изображения").render(pages.first())
        assertTrue(surface.accessibleText.contains("Недоступная иллюстрация"))
        assertTrue(surface.accessibleText.contains("Текст продолжается."))
    }

    @Test fun renderingRepeatedFramesUsesTheSameBitmapWithoutTextLayoutOrDecoding() = runBlocking {
        val document = doc(BookBlock.Paragraph(BlockId("p"), listOf(InlineNode.Text("Тихий город. ".repeat(100)))))
        val pages = IncrementalPaginationEngine(AndroidBookMeasurer(document, "Нет изображения"))
            .paginate(document, null, key).last().pages
        val rasterizer = AndroidPageRasterizer(document, key, "Нет изображения")
        val first = rasterizer.render(pages.first())
        rasterizer.render(pages[1])
        assertEquals(1L, rasterizer.measureCount)
        val target = Bitmap.createBitmap(key.pageWidthPx, key.pageHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        val bounds = android.graphics.RectF(0f, 0f, key.pageWidthPx.toFloat(), key.pageHeightPx.toFloat())
        repeat(120) { first.drawTo(canvas, bounds) }
        assertEquals(1L, rasterizer.measureCount)
        assertEquals(2L, rasterizer.renderCount)
        assertTrue(first.accessibleText.startsWith("Тихий город."))
        assertFalse(first.bitmap.isRecycled)
    }

    @Test fun hugeViewportIsResolutionCappedAndEvictionDoesNotRecycleAnActiveLeaf() = runBlocking {
        val document = doc(*Array(40) { BookBlock.Paragraph(BlockId("p$it"),
            listOf(InlineNode.Text("Белые страницы хранят историю. ".repeat(50)))) })
        val largeKey = key.copy(pageWidthPx = 4000, pageHeightPx = 4000, density = 5f, pageMode = PageMode.SPREAD)
        val pages = IncrementalPaginationEngine(AndroidBookMeasurer(document, "Нет изображения"))
            .paginate(document, null, largeKey).last().pages
        assertTrue(pages.size >= 12)
        val rasterizer = AndroidPageRasterizer(document, largeKey, "Нет изображения")
        val store = SurfaceStore<Int, PaginatedPageSurface>(this, Dispatchers.Default, PAGE_CACHE_BYTES, { it.byteCount }) {
            rasterizer.render(pages[it])
        }
        try {
            store.request((0..5).toList(), listOf(6, 7))
            val first = withTimeout(15_000) { store.state.first { 7 in it.values } }.values.getValue(0)
            assertTrue(first.bitmap.width.toLong() * first.bitmap.height <= 2_097_152)
            assertTrue(first.byteCount <= 8L * 1024 * 1024)
            store.request((6..11).toList())
            withTimeout(15_000) { store.state.first { 11 in it.values } }
            assertTrue(store.state.value.bytes <= PAGE_CACHE_BYTES)
            assertTrue(store.state.value.evictions > 0)
            store.trimMemory()
            assertEquals((6..11).toSet(), store.state.value.values.keys)
            assertFalse(first.bitmap.isRecycled)
            assertTrue(first.bitmap.getPixel(0, 0) == 0) // Transparent surface; white paper is a separate layer.
        } finally { store.close() }
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

    @Test fun restoredCheckpointKeepsExactSourceOffsetAndEveryCoverPoseAcrossReflow() = runBlocking {
        val source = BookBlock.Paragraph(BlockId("p"), listOf(InlineNode.Text("Source anchor 😀. ".repeat(300))))
        val document = doc(source)
        val location = BookLocation(source.id, 1200, 1200)
        val snapshot = IncrementalPaginationEngine(AndroidBookMeasurer(document, "Missing"))
            .paginate(document, location, key.copy(pageWidthPx = 280, pageMode = PageMode.SPREAD)).last()
        CoverPosition.entries.forEach { cover ->
            val restored = ReaderPosition(location, cover.name.lowercase())
            val expected = when (cover) {
                CoverPosition.OPEN -> PhysicalBookState.Open(snapshot.anchorPageIndex)
                CoverPosition.FRONT -> PhysicalBookState.ClosedFront
                CoverPosition.BACK -> PhysicalBookState.ClosedBack
                CoverPosition.BOUNDARY -> PhysicalBookState.BackBoundary
            }
            assertEquals(expected, restored.initialState(snapshot))
            assertEquals(location, restored.location) // Reflow never rounds persistence to the page start.
            assertEquals(cover, restored.progress(document.readingLength()).cover)
        }
    }
}

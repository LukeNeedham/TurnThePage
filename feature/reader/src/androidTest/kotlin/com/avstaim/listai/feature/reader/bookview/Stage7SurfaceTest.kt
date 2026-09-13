package com.avstaim.listai.feature.reader.bookview

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Stage7SurfaceTest {
    @Test fun originalCoverUsesViewportResolutionAndRejectsBrokenImages() {
        val image = image(1200, 1800)
        val document = document(image)
        val decoded = requireNotNull(decodeCover(document, IntSize(1080, 1920))).asAndroidBitmap()
        assertTrue("Reader must not stretch the 512 px shelf thumbnail", decoded.width > 512)
        assertEquals(2f / 3f, decoded.width.toFloat() / decoded.height, .001f)
        assertEquals(SOURCE_COLOR, decoded.getPixel(decoded.width / 2, decoded.height / 2))
        assertNull(decodeCover(document(image.copy(content = BookBinaryContent { byteArrayOf(1, 2, 3) })), IntSize(1000, 1500)))
    }

    @Test fun darkTextChangesButImagePixelsAndLayoutStayOriginal() = runBlocking {
        val source = document(image(200, 100))
        val lightKey = LayoutKey(600, 900, PageMode.SINGLE)
        val darkKey = lightKey.copy(darkTheme = true)
        val light = IncrementalPaginationEngine(AndroidBookMeasurer(source, "Missing")).paginate(source, null, lightKey).last()
        val dark = IncrementalPaginationEngine(AndroidBookMeasurer(source, "Missing")).paginate(source, null, darkKey).last()
        assertEquals(light.pages.map { it.start }, dark.pages.map { it.start })
        val a = AndroidPageRasterizer(source, lightKey, "Missing").render(light.pages.first())
        val b = AndroidPageRasterizer(source, darkKey, "Missing").render(dark.pages.first())
        val picture = light.pages.first().blocks.first { it.blockId.value == "image" }
        val x = (picture.x + picture.width / 2).toInt()
        val y = (picture.y + picture.height / 2).toInt()
        assertEquals(SOURCE_COLOR, a.bitmap.getPixel(x, y))
        assertEquals(SOURCE_COLOR, b.bitmap.getPixel(x, y))
        assertFalse(a.darkTheme)
        assertTrue(b.darkTheme)
        assertTrue((0 until a.bitmap.height).any { row -> (0 until a.bitmap.width).any { column ->
            a.bitmap.getPixel(column, row) != b.bitmap.getPixel(column, row)
        } })
    }

    private fun image(width: Int, height: Int) = BookImage(ImageId("cover"), "image/png", BookBinaryContent {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(SOURCE_COLOR)
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    })

    private fun document(image: BookImage) = BookDocument(BookId("source-image"),
        BookMetadata("Original", coverImageId = image.id), listOf(
            BookBlock.Paragraph(BlockId("text"), listOf(InlineNode.Text("Original text stays readable. ".repeat(3)))),
            BookBlock.Image(BlockId("image"), image.id),
        ), images = listOf(image))

    private companion object { const val SOURCE_COLOR = 0xFFC76D43.toInt() }
}

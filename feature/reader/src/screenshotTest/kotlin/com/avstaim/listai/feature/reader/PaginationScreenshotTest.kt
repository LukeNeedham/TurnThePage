package com.avstaim.listai.feature.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.bookview.DemoTurnPreview
import com.avstaim.listai.feature.reader.bookview.PhysicalBookView
import com.avstaim.listai.reader.rendering.turn.TurnDirection

@PreviewTest
@Preview(name = "Pagination — Cyrillic portrait", widthDp = 412, heightDp = 800, locale = "ru")
@Composable
fun PaginatedPortraitScreenshot() { PaginationPreview() }

@PreviewTest
@Preview(name = "Pagination — landscape spread", widthDp = 1000, heightDp = 650, locale = "ru")
@Composable
fun PaginatedSpreadScreenshot() { PaginationPreview(restore = true) }

@PreviewTest
@Preview(name = "Pagination — live text on curl", widthDp = 412, heightDp = 800, locale = "ru")
@Composable
fun PaginatedCurlScreenshot() { PaginationPreview(curl = true) }

@PreviewTest
@Preview(name = "Pagination — large font", widthDp = 412, heightDp = 800, fontScale = 1.5f, locale = "ru")
@Composable
fun PaginatedLargeFontScreenshot() { PaginationPreview() }

@PreviewTest
@Preview(name = "Pagination — image block and fallback", widthDp = 412, heightDp = 800, locale = "ru")
@Composable
fun PaginatedImageScreenshot() {
    val document = remember {
        val image = BookImage(ImageId("illustration"), "image/png", BookBinaryContent {
            val bitmap = Bitmap.createBitmap(720, 400, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFFE4E9E8.toInt())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = 0xFF788F88.toInt()
            canvas.drawCircle(540f, 100f, 50f, paint)
            paint.color = 0xFF344C44.toInt()
            canvas.drawRect(0f, 280f, 720f, 400f, paint)
            paint.color = 0xFFBFCBC6.toInt()
            canvas.drawOval(80f, 200f, 660f, 350f, paint)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        })
        BookDocument(BookId("image-preview"), BookMetadata("Атлас", language = "ru"), listOf(
            BookBlock.Heading(BlockId("h"), 1, listOf(InlineNode.Text("Дорога к реке"))),
            BookBlock.Image(BlockId("picture"), image.id, "Река и вечернее солнце"),
            BookBlock.Paragraph(BlockId("p"), listOf(InlineNode.Text("Иллюстрация вписана целиком. Текст продолжается под изображением и остаётся частью того же потока."))),
            BookBlock.Image(BlockId("missing"), ImageId("absent"), "Изображение недоступно"),
            BookBlock.Paragraph(BlockId("last"), listOf(InlineNode.Text("Отсутствующее изображение не мешает продолжить чтение."))),
        ), images = listOf(image))
    }
    ListaiTheme(darkTheme = false) {
        PhysicalBookView(emptyList(), document.metadata.title, null, document = document)
    }
}

@Composable
private fun PaginationPreview(curl: Boolean = false, restore: Boolean = false) {
    val document = remember {
        BookDocument(BookId("screenshot"), BookMetadata("Атлас тихих страниц", listOf("Мастерская открытой книги"), "ru"),
            listOf(BookBlock.Heading(BlockId("h"), 1, listOf(InlineNode.Text("Утро в мастерской")))) +
                List(12) { index -> BookBlock.Paragraph(BlockId("p$index"), listOf(
                    InlineNode.Text("Запись ${index + 1}. Город ещё не проснулся. На мостовой блестели следы ночного дождя. "),
                    InlineNode.Emphasis(listOf(InlineNode.Text("Сохранить место"))),
                    InlineNode.Text(" — значит сохранить возможность продолжить. Марк поставил чайник и раскрыл книгу. "),
                    InlineNode.Strong(listOf(InlineNode.Text("Белые листы"))),
                    InlineNode.Text(" хранили историю дороги через лес, мимо реки, к старой обсерватории. ".repeat(3)),
                )) })
    }
    ListaiTheme(darkTheme = false) {
        PhysicalBookView(emptyList(), document.metadata.title, document.metadata.authors.joinToString(), document = document,
            initialLocation = if (restore) BookLocation(BlockId("p4"), 180, 0) else null,
            previewTurn = if (curl) DemoTurnPreview(TurnDirection.FORWARD, fraction = .45f) else null)
    }
}

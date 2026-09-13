package com.avstaim.listai.feature.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.bookview.*
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import java.io.ByteArrayOutputStream

@PreviewTest
@Preview(name = "Contents — first physical leaf", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun PhysicalContentsEnglishScreenshot() { Stage7BookPreview(toc = true) }

@PreviewTest
@Preview(name = "Contents — graphite spread", widthDp = 1000, heightDp = 650, locale = "ru")
@Composable
fun PhysicalContentsDarkSpreadScreenshot() { Stage7BookPreview(toc = true, dark = true) }

@PreviewTest
@Preview(name = "Contents — paper curl", widthDp = 412, heightDp = 800, locale = "ru")
@Composable
fun PhysicalContentsCurlScreenshot() { Stage7BookPreview(toc = true, curl = true) }

@PreviewTest
@Preview(name = "Original cover — portrait fit", widthDp = 412, heightDp = 800)
@Composable
fun OriginalCoverScreenshot() { Stage7BookPreview(cover = true) }

@PreviewTest
@Preview(name = "Original cover — rigid opening in dark", widthDp = 1000, heightDp = 650)
@Composable
fun OriginalCoverOpeningDarkScreenshot() { Stage7BookPreview(cover = true, curl = true, dark = true) }

@PreviewTest
@Preview(name = "Reading — graphite and ribbon", widthDp = 412, heightDp = 800, locale = "ru")
@Composable
fun DarkBookRibbonScreenshot() { Stage7BookPreview(dark = true, ribbon = true) }

@Composable
private fun Stage7BookPreview(toc: Boolean = false, cover: Boolean = false, curl: Boolean = false,
    dark: Boolean = false, ribbon: Boolean = false) {
    val document = remember { stage7PreviewDocument() }
    ListaiTheme(darkTheme = dark) {
        PhysicalBookView(emptyList(), document.metadata.title, document.metadata.authors.joinToString(),
            document = document, darkTheme = dark, includeContents = true,
            initialTocEntryIndex = if (toc) 0 else null,
            initialCover = if (cover) CoverPosition.FRONT else CoverPosition.OPEN,
            outerPadding = 4.dp, coverInset = 3.dp,
            previewTurn = if (curl) DemoTurnPreview(TurnDirection.FORWARD, .42f) else null,
            bookmarks = if (ribbon) listOf(Bookmark("ribbon", document.id,
                BookLocation(document.blocks.first().id, 0, 0), 0, "Тихий город")) else emptyList())
    }
}

internal fun stage7PreviewDocument(): BookDocument {
    val blocks = List(28) { i -> BookBlock.Paragraph(BlockId("entry-$i"), listOf(
        InlineNode.Text("Глава ${i + 1}. Тихий город просыпался под светлым небом. " +
            "Белые страницы хранили историю дороги через лес, мимо реки, к обсерватории. ".repeat(5)))) }
    var offset = 0L
    val entries = blocks.mapIndexed { i, block ->
        BookTocEntry("${i + 1}. " + listOf("The morning library", "A path beside the river",
            "Наблюдения за небом", "Заметки о путешествии")[i % 4], BookLocation(block.id, 0, offset)).also {
            offset += block.plainText().length + 1
        }
    }
    val image = BookImage(ImageId("original-cover"), "image/png", BookBinaryContent {
        val bitmap = Bitmap.createBitmap(900, 1350, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF122C45.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFFB4D4E5.toInt()
        canvas.drawCircle(640f, 620f, 210f, paint)
        paint.color = 0xFFD17D55.toInt() // The actual cover keeps this source colour in dark mode.
        canvas.drawRect(0f, 800f, 900f, 1010f, paint)
        paint.color = 0xFFF5F9FC.toInt()
        paint.textSize = 74f
        canvas.drawText("THE BLUE", 80f, 190f, paint)
        canvas.drawText("ATLAS", 80f, 280f, paint)
        paint.textSize = 35f
        canvas.drawText("E. NORTH", 80f, 1220f, paint)
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    })
    return BookDocument(BookId("stage7-preview"), BookMetadata("The Blue Atlas", listOf("E. North"),
        language = "ru", coverImageId = image.id), blocks, tableOfContents = entries, images = listOf(image))
}

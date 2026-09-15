package com.avstaim.listai.feature.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.BookBinaryContent
import com.avstaim.listai.core.model.BookImage
import com.avstaim.listai.core.model.CoverPosition
import com.avstaim.listai.core.model.ImageId
import com.avstaim.listai.feature.reader.bookview.DemoTurnPreview
import com.avstaim.listai.feature.reader.bookview.PhysicalBookView
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import java.io.ByteArrayOutputStream

@PreviewTest
@Preview(name = "Original artwork 7.2.1 — portrait light", widthDp = 412, heightDp = 800)
@Composable
fun OriginalPortraitBindingScreenshot() { OriginalArtworkPreview(IntSize(240, 360)) }

@PreviewTest
@Preview(name = "Original artwork 7.2.1 — wide light", widthDp = 412, heightDp = 800)
@Composable
fun OriginalWideBindingScreenshot() { OriginalArtworkPreview(IntSize(420, 210)) }

@PreviewTest
@Preview(name = "Original artwork 7.2.1 — square dark", widthDp = 412, heightDp = 800)
@Composable
fun OriginalSquareBindingScreenshot() { OriginalArtworkPreview(IntSize(280, 280), dark = true) }

@PreviewTest
@Preview(name = "Original artwork 7.2.1 — forward wide light", widthDp = 412, heightDp = 800)
@Composable
fun OriginalWideForwardBindingScreenshot() {
    OriginalArtworkPreview(IntSize(420, 210), turn = DemoTurnPreview(TurnDirection.FORWARD, .28f))
}

@PreviewTest
@Preview(name = "Original artwork 7.2.1 — forward portrait dark spread", widthDp = 1000, heightDp = 650)
@Composable
fun OriginalPortraitForwardBindingScreenshot() {
    OriginalArtworkPreview(IntSize(240, 360), dark = true, turn = DemoTurnPreview(TurnDirection.FORWARD, .3f))
}

@PreviewTest
@Preview(name = "Original artwork 7.2.1 — backward wide light", widthDp = 412, heightDp = 800)
@Composable
fun OriginalWideBackwardBindingScreenshot() {
    OriginalArtworkPreview(IntSize(420, 210), turn = DemoTurnPreview(TurnDirection.BACKWARD, .72f))
}

@PreviewTest
@Preview(name = "Original artwork 7.2.1 — backward portrait dark spread", widthDp = 1000, heightDp = 650)
@Composable
fun OriginalPortraitBackwardBindingScreenshot() {
    OriginalArtworkPreview(IntSize(240, 360), dark = true, turn = DemoTurnPreview(TurnDirection.BACKWARD, .7f))
}

@PreviewTest
@Preview(name = "Original artwork 7.2.1 — square edge on", widthDp = 1000, heightDp = 650)
@Composable
fun OriginalSquareEdgeOnBindingScreenshot() {
    OriginalArtworkPreview(IntSize(280, 280), turn = DemoTurnPreview(TurnDirection.FORWARD, .5f))
}

@Composable
private fun OriginalArtworkPreview(imageSize: IntSize, dark: Boolean = false, turn: DemoTurnPreview? = null) {
    val document = remember(imageSize) {
        val original = stage7PreviewDocument()
        val image = BookImage(ImageId("warm-original-cover"), "image/png", BookBinaryContent {
            val bitmap = Bitmap.createBitmap(imageSize.width, imageSize.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xffe4d5c4.toInt())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff8a492d.toInt() }
            canvas.drawCircle(imageSize.width / 2f, imageSize.height / 2f,
                minOf(imageSize.width, imageSize.height) * .22f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            canvas.drawRect(4f, 4f, imageSize.width - 4f, imageSize.height - 4f, paint)
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = minOf(imageSize.width, imageSize.height) * .065f
            canvas.drawText("THE QUIET CITY", imageSize.width / 2f, paint.textSize * 1.7f, paint)
            canvas.drawText("FULL ORIGINAL ART", imageSize.width / 2f, imageSize.height - paint.textSize, paint)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                .also { bitmap.recycle() }
        })
        original.copy(metadata = original.metadata.copy(title = "The Quiet City", coverImageId = image.id),
            images = listOf(image))
    }
    ListaiTheme(darkTheme = dark) {
        PhysicalBookView(emptyList(), document.metadata.title, "E. North", document = document,
            includeContents = true, darkTheme = dark, outerPadding = 4.dp, coverInset = 3.dp,
            initialCover = if (turn?.direction == TurnDirection.BACKWARD) CoverPosition.OPEN else CoverPosition.FRONT,
            initialTocEntryIndex = if (turn?.direction == TurnDirection.BACKWARD) 0 else null,
            previewTurn = turn)
    }
}

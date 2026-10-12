package com.avstaim.listai.feature.reader

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.adaptive.DevicePosture
import com.avstaim.listai.core.adaptive.FoldBounds
import com.avstaim.listai.core.adaptive.FoldOrientation
import com.avstaim.listai.core.designsystem.ListaiTheme

@PreviewTest
@Preview(name = "PDF — tall phone ru", widthDp = 320, heightDp = 700, locale = "ru")
@Preview(name = "PDF — tall dark phone en", widthDp = 412, heightDp = 915, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "PDF — landscape en", widthDp = 740, heightDp = 360, locale = "en")
@Preview(name = "PDF — landscape dark ru large type", widthDp = 740, heightDp = 360, locale = "ru", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PdfPageViewerScreenshots() { PdfViewerPreview() }

@PreviewTest
@Preview(name = "PDF — Fold hinge ru", widthDp = 673, heightDp = 841, locale = "ru")
@Preview(name = "PDF — dark Fold hinge en", widthDp = 673, heightDp = 841, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PdfPageViewerFoldScreenshots() {
    val density = LocalDensity.current
    val posture = remember(density) {
        val width = with(density) { 673.dp.roundToPx() }
        val height = with(density) { 841.dp.roundToPx() }
        val halfHinge = with(density) { 10.dp.roundToPx() }
        DevicePosture.Flat(FoldBounds(width / 2 - halfHinge, 0, width / 2 + halfHinge, height),
            FoldOrientation.VERTICAL, separating = true, occluding = true)
    }
    PdfViewerPreview(posture)
}

@Composable
private fun PdfViewerPreview(posture: DevicePosture = DevicePosture.Unknown) {
    val dark = isSystemInDarkTheme()
    val bitmap = remember {
        Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            val canvas = Canvas(this)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.RED
            canvas.drawRect(0f, 0f, 60f, 60f, paint)
            paint.color = Color.BLUE
            canvas.drawRect(540f, 840f, 600f, 900f, paint)
            paint.color = Color.BLACK
            paint.textSize = 24f
            canvas.drawText("PDF source page 7", 30f, 90f, paint)
            paint.textSize = 14f
            repeat(35) { line ->
                canvas.drawText("Prepared source detail — pinch, pan and double tap", 30f, 130f + line * 18f, paint)
            }
        }.asImageBitmap()
    }
    ListaiTheme(darkTheme = dark) {
        BookImageViewerContent(bitmap, alternativeText = null, onDismiss = {},
            imageId = "pdf:fixture-7", devicePosture = posture, pdfPageNumber = 7,
            fitHeightOnOpen = true, darkTheme = dark)
    }
}

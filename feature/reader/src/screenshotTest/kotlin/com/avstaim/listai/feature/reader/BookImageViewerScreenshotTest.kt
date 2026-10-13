package com.avstaim.listai.feature.reader

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.adaptive.DevicePosture
import com.avstaim.listai.core.adaptive.FoldBounds
import com.avstaim.listai.core.adaptive.FoldOrientation
import com.avstaim.listai.core.designsystem.ListaiTheme

@PreviewTest
@Preview(name = "Иллюстрация — весь экран ru", widthDp = 412, heightDp = 850, locale = "ru")
@Preview(name = "Illustration — landscape en", widthDp = 740, heightDp = 360, locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Иллюстрация — крупный шрифт ru", widthDp = 320, heightDp = 700, locale = "ru",
    fontScale = 2f)
@Composable
fun BookImageViewerFitScreenshots() {
    ImageViewerPreview()
}

@PreviewTest
@Preview(name = "Illustration — open Fold hinge en", widthDp = 673, heightDp = 841, locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun BookImageViewerFoldScreenshot() {
    val density = LocalDensity.current
    val posture = remember(density) {
        val width = with(density) { 673.dp.roundToPx() }
        val height = with(density) { 841.dp.roundToPx() }
        val halfHinge = with(density) { 10.dp.roundToPx() }
        DevicePosture.Flat(
            foldBounds = FoldBounds(width / 2 - halfHinge, 0, width / 2 + halfHinge, height),
            orientation = FoldOrientation.VERTICAL,
            separating = true,
            occluding = true,
        )
    }
    ImageViewerPreview(devicePosture = posture)
}

@PreviewTest
@Preview(name = "Illustration — tabletop hinge en", widthDp = 740, heightDp = 600, locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun BookImageViewerTabletopScreenshot() {
    val density = LocalDensity.current
    val posture = remember(density) {
        val width = with(density) { 740.dp.roundToPx() }
        val height = with(density) { 600.dp.roundToPx() }
        val halfHinge = with(density) { 8.dp.roundToPx() }
        DevicePosture.Flat(
            foldBounds = FoldBounds(0, height / 2 - halfHinge, width, height / 2 + halfHinge),
            orientation = FoldOrientation.HORIZONTAL,
            separating = true,
            occluding = true,
        )
    }
    ImageViewerPreview(devicePosture = posture)
}

@PreviewTest
@Preview(name = "Illustration — zoomed closeup en", widthDp = 740, heightDp = 360, locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun BookImageViewerZoomedScreenshot() {
    ImageViewerPreview(initialTransform = BookImageZoom(scale = 3f, x = -180f, y = 90f))
}

@PreviewTest
@Preview(name = "Иллюстрация — загрузка ru", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun BookImageViewerLoadingScreenshot() {
    ImageViewerPreview(bitmapAvailable = false, isLoading = true)
}

@PreviewTest
@Preview(name = "Illustration — unavailable at large font en", widthDp = 320, heightDp = 700,
    locale = "en", fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun BookImageViewerUnavailableScreenshot() {
    ImageViewerPreview(bitmapAvailable = false)
}

@PreviewTest
@Preview(name = "Иллюстрация — раскрытие из страницы ru", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun BookImageViewerEnteringScreenshot() {
    ImageViewerMotionPreview(transitionProgress = .45f, originalAvailable = false)
}

@PreviewTest
@Preview(name = "Illustration — enlarged image returns to page en", widthDp = 412, heightDp = 850,
    locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun BookImageViewerExitingScreenshot() {
    ImageViewerMotionPreview(transitionProgress = .2f,
        initialTransform = BookImageZoom(scale = 3f, x = -100f, y = 80f))
}

@PreviewTest
@Preview(name = "Иллюстрация — удержание на странице ru", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun BookImageViewerTransientHoldScreenshot() {
    ImageViewerMotionPreview(transientPreview = true)
}

@PreviewTest
@Preview(name = "Illustration — temporary held detail en", widthDp = 412, heightDp = 850,
    locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun BookImageViewerHeldDetailScreenshot() {
    ImageViewerMotionPreview(initialHeld = true)
}

@Composable
private fun ImageViewerMotionPreview(
    transitionProgress: Float = 1f,
    originalAvailable: Boolean = true,
    transientPreview: Boolean = false,
    initialHeld: Boolean = false,
    initialTransform: BookImageZoom = BookImageZoom(),
) {
    val fixture = remember { illustrationOnPaper() }
    val density = LocalDensity.current
    val sourceBounds = with(density) { Rect(66.dp.toPx(), 150.dp.toPx(), 346.dp.toPx(), 346.dp.toPx()) }
    ListaiTheme(darkTheme = isSystemInDarkTheme()) {
        Box(Modifier.fillMaxSize()) {
            ComposeCanvas(Modifier.fillMaxSize()) {
                drawImage(fixture.page, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            }
            Box(Modifier.fillMaxSize().drawBehind {
                drawRect(Color.White, sourceBounds.topLeft, sourceBounds.size)
            })
            BookImageViewerContent(
                bitmap = fixture.image.takeIf { originalAvailable },
                isLoading = !originalAvailable,
                alternativeText = "Observatory above a mountain lake",
                onDismiss = {},
                preview = BookImagePreview(fixture.page, IntRect(66, 150, 346, 346)),
                sourceBounds = sourceBounds,
                transitionProgress = transitionProgress,
                transientPreview = transientPreview,
                initialHeld = initialHeld,
                initialTransform = initialTransform,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ImageViewerPreview(bitmapAvailable: Boolean = true, isLoading: Boolean = false,
    initialTransform: BookImageZoom = BookImageZoom(),
    devicePosture: DevicePosture = DevicePosture.Unknown) {
    val bitmap = remember { observatoryIllustration() }
    ListaiTheme(darkTheme = isSystemInDarkTheme()) {
        BookImageViewerContent(
            bitmap = bitmap.takeIf { bitmapAvailable },
            alternativeText = "Observatory above a mountain lake",
            onDismiss = {},
            isLoading = isLoading,
            initialTransform = initialTransform,
            devicePosture = devicePosture,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private data class IllustrationSourceFixture(val image: ImageBitmap, val page: ImageBitmap)

/** The transition uses a retained page crop, including its raster sampling at the source size. */
private fun illustrationOnPaper(): IllustrationSourceFixture {
    val image = observatoryIllustration()
    val page = Bitmap.createBitmap(412, 850, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(page)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.drawColor(0xFFDDE4EB.toInt())
    paint.color = 0xFFD1DAE3.toInt()
    canvas.drawRect(32f, 32f, 384f, 828f, paint)
    paint.color = 0xFFFFFFFF.toInt()
    canvas.drawRect(28f, 28f, 380f, 822f, paint)
    paint.color = 0xFFADB7C1.toInt()
    paint.strokeWidth = 1.5f
    repeat(3) { row -> canvas.drawLine(66f, 92f + row * 14f, 336f - row * 12f, 92f + row * 14f, paint) }
    canvas.drawBitmap(image.asAndroidBitmap(), null, RectF(66f, 150f, 346f, 346f), paint)
    repeat(21) { row ->
        val y = 376f + row * 16f
        canvas.drawLine(66f, y, if (row % 5 == 4) 290f else 344f, y, paint)
    }
    paint.color = 0xFF8294A4.toInt()
    canvas.drawCircle(204f, 790f, 2f, paint)
    return IllustrationSourceFixture(image, page.asImageBitmap())
}

/** A fixed-resolution book plate: borders and fine lines expose fit, cropping, and zoom errors. */
internal fun observatoryIllustration(): ImageBitmap {
    val bitmap = Bitmap.createBitmap(1200, 840, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun fill(color: Long) {
        paint.color = color.toInt()
        paint.style = Paint.Style.FILL
    }
    fun stroke(color: Long, width: Float) {
        paint.color = color.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
    }
    canvas.drawColor(0xFFF7F8FA.toInt())
    stroke(0xFFB6C1CC, 3f)
    canvas.drawRect(28f, 28f, 1172f, 812f, paint)
    fill(0xFFE1EAF0)
    canvas.drawRect(48f, 48f, 1152f, 620f, paint)
    fill(0xFFF3F6F9)
    canvas.drawCircle(258f, 196f, 74f, paint)

    val distantMountain = Path().apply {
        moveTo(48f, 492f)
        lineTo(286f, 330f)
        lineTo(432f, 428f)
        lineTo(680f, 258f)
        lineTo(936f, 416f)
        lineTo(1152f, 300f)
        lineTo(1152f, 662f)
        lineTo(48f, 662f)
        close()
    }
    fill(0xFFAFBFCA)
    canvas.drawPath(distantMountain, paint)
    val foregroundMountain = Path().apply {
        moveTo(48f, 620f)
        cubicTo(190f, 536f, 264f, 548f, 356f, 514f)
        cubicTo(476f, 450f, 618f, 484f, 760f, 394f)
        lineTo(928f, 398f)
        cubicTo(1040f, 434f, 1060f, 500f, 1152f, 550f)
        lineTo(1152f, 688f)
        lineTo(48f, 688f)
        close()
    }
    fill(0xFF71899A)
    canvas.drawPath(foregroundMountain, paint)

    fill(0xFFD9E5EC)
    canvas.drawRect(48f, 652f, 1152f, 792f, paint)
    stroke(0xFFACC1CF, 3f)
    repeat(5) { row ->
        val y = 680f + row * 21f
        canvas.drawLine(118f + row * 12f, y, 602f - row * 28f, y, paint)
        canvas.drawLine(754f + row * 18f, y, 1090f - row * 11f, y, paint)
    }

    fill(0xFFF3F5F7)
    canvas.drawRect(760f, 304f, 930f, 398f, paint)
    canvas.drawArc(RectF(760f, 230f, 930f, 378f), 180f, 180f, true, paint)
    stroke(0xFF425B70, 5f)
    canvas.drawRect(760f, 304f, 930f, 398f, paint)
    canvas.drawArc(RectF(760f, 230f, 930f, 378f), 180f, 180f, false, paint)
    canvas.drawLine(754f, 304f, 936f, 304f, paint)
    canvas.drawLine(845f, 232f, 845f, 304f, paint)
    fill(0xFF607D94)
    canvas.drawRect(796f, 334f, 820f, 372f, paint)
    canvas.drawRect(864f, 334f, 888f, 372f, paint)
    stroke(0xFFCBD8E0, 5f)
    val path = Path().apply {
        moveTo(856f, 405f)
        cubicTo(812f, 456f, 926f, 482f, 796f, 534f)
        cubicTo(712f, 568f, 680f, 598f, 718f, 644f)
    }
    canvas.drawPath(path, paint)
    return bitmap.asImageBitmap()
}

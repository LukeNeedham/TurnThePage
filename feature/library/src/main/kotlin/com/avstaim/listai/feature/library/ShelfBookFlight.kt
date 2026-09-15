package com.avstaim.listai.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.model.LibraryBook
import com.avstaim.listai.core.designsystem.BookCoverFace
import kotlin.math.roundToInt
import kotlin.math.sin

/** Deterministic geometry is shared by the live flight and its fixed-time screenshot fixtures. */
fun shelfBookFlightRect(source: Rect, destination: Rect, progress: Float): Rect {
    val t = progress.coerceIn(0f, 1f)
    fun mix(a: Float, b: Float) = a + (b - a) * t
    val lift = (sin(t * Math.PI).toFloat() * minOf(source.height * .18f, destination.height * .06f))
        .coerceAtMost(mix(source.top, destination.top).coerceAtLeast(0f))
    return Rect(mix(source.left, destination.left), mix(source.top, destination.top) - lift,
        mix(source.right, destination.right), mix(source.bottom, destination.bottom) - lift)
}

/** An imported/offscreen book enters along the same lower-shelf trajectory after resize. */
fun shelfBookSourceRect(measured: Rect?, viewport: Size, density: Float): Rect {
    if (measured != null && measured.width > 0f && measured.height > 0f &&
        measured.left < viewport.width && measured.right > 0f &&
        measured.top >= 0f && measured.bottom <= viewport.height) return measured
    val width = minOf(154f * density, viewport.width * .36f).coerceAtLeast(1f)
    val left = (viewport.width - width) / 2f
    val top = viewport.height + 18f * density
    return Rect(left, top, left + width, top + width * 1.48f)
}

@Composable
fun ShelfBookFlight(
    book: LibraryBook,
    source: Rect,
    destination: Rect,
    progress: Float,
    modifier: Modifier = Modifier,
    backCover: Boolean = false,
    previewImage: ImageBitmap? = null,
) {
    val density = LocalDensity.current
    val rect = shelfBookFlightRect(source, destination, progress)
    Box(modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
        .requiredSize(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
        .graphicsLayer {
            if (backCover) {
                rotationY = if (progress < .5f) progress * 180f else progress * 180f - 180f
                cameraDistance = 12f * density.density
            }
        }
        .testTag("shelf_flying_book")
        .shadow((7f + 15f * sin(progress.coerceIn(0f, 1f) * Math.PI).toFloat()).dp, RoundedCornerShape(2.dp))) {
        LibraryCover(book, previewImage, face = if (backCover && progress >= .5f) BookCoverFace.BACK else BookCoverFace.FRONT)
    }
}

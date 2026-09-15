package com.avstaim.listai.feature.reader.bookview

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntSize
import com.avstaim.listai.core.designsystem.BookCoverFace
import com.avstaim.listai.core.designsystem.BookCoverSurface
import com.avstaim.listai.core.designsystem.drawBookCover
import com.avstaim.listai.core.designsystem.rememberBookCoverSurface
import com.avstaim.listai.core.model.BookDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

internal typealias ExternalBookCoverFace = BookCoverFace
internal typealias ExternalBookCoverSurface = BookCoverSurface

/** Shelf and physical book use the same unprojected artwork and density-correct lettering. */
@Composable
internal fun rememberExternalBookCoverSurface(
    title: String,
    author: String?,
    coverSize: IntSize,
    document: BookDocument? = null,
    darkTheme: Boolean = false,
): ExternalBookCoverSurface {
    val inspection = LocalInspectionMode.current
    val preview = remember(document, coverSize, inspection) {
        if (inspection) decodeCover(document, coverSize) else null
    }
    val decoded by produceState(
        preview to (inspection || document?.metadata?.coverImageId == null), document, coverSize,
    ) {
        value = withContext(Dispatchers.Default) { decodeCover(document, coverSize) } to true
    }
    return rememberBookCoverSurface(title, author, coverSize, decoded.first, darkTheme)
        .copy(isReady = decoded.second)
}

internal fun DrawScope.drawExternalBookCover(
    surface: ExternalBookCoverSurface,
    bounds: Rect,
    face: ExternalBookCoverFace,
    side: PageSide,
    contentAlpha: Float = 1f,
) = drawBookCover(surface, bounds, face, spineOnLeft = side != PageSide.LEFT, contentAlpha = contentAlpha)

/** Decode once outside animation frames, respecting both resolution and memory limits. */
internal fun decodeCover(document: BookDocument?, size: IntSize): ImageBitmap? = runCatching {
    val bytes = document?.metadata?.coverImageId?.let { document.imagesById[it] }?.content?.load()
        ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    // Full-board print needs enough pixels along both axes, not only the old fitted thumbnail.
    val fit = max(size.width.toFloat() / bounds.outWidth, size.height.toFloat() / bounds.outHeight)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= bounds.outWidth * fit &&
        bounds.outHeight / (sample * 2) >= bounds.outHeight * fit && sample < 16384) sample *= 2
    while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 4_194_304L) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = sample
    })?.also { it.prepareToDraw() }?.asImageBitmap()
}.getOrNull()

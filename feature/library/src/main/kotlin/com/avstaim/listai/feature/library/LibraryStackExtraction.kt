package com.avstaim.listai.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.model.LibraryBook
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class LibraryBookExtraction(val book: LibraryBook, val from: Rect, val target: Rect? = null)

/** Replaces the stack heading only while a book is held; never competes for a pointer stream. */
@Composable
internal fun LibraryStackExtractionTarget(ready: Boolean, onBounds: (Rect?) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    DisposableEffect(Unit) { onDispose { onBounds(null) } }
    Surface(modifier.onGloballyPositioned { onBounds(it.boundsInRoot()) }.testTag("library_extract_target"),
        color = if (ready) colors.primaryContainer else colors.surface,
        contentColor = if (ready) colors.onPrimaryContainer else colors.onSurface,
        shape = RoundedCornerShape(10.dp), shadowElevation = if (ready) 8.dp else 3.dp,
        border = BorderStroke(if (ready) 2.dp else 1.dp, if (ready) colors.primary else colors.outlineVariant)) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            LibrarySheetIcon(LibraryMenuIcon.EXTRACT, Modifier.size(24.dp))
            Text(stringResource(R.string.library_extract_drop), style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** Fly into the measured root-shelf slot, retaining the same cover throughout the transition. */
@Composable
internal fun LibraryExtractedBookFlight(
    extraction: LibraryBookExtraction, progress: Float, rootOrigin: Offset,
    coverContent: @Composable (LibraryBook) -> Unit,
) {
    val from = extraction.from
    val to = extraction.target ?: from
    val p = progress.coerceIn(0f, 1f)
    val density = LocalDensity.current
    val point = from.topLeft + (to.topLeft - from.topLeft) * p - rootOrigin
    val arc = with(density) { 32.dp.toPx() } * sin(p * Math.PI).toFloat()
    val width = with(density) { (from.width + (to.width - from.width) * p).toDp() }
    val height = with(density) { (from.height + (to.height - from.height) * p).toDp() }
    Box(Modifier.offset { IntOffset(point.x.roundToInt(), (point.y - arc).roundToInt()) }
        .size(width, height).graphicsLayer { rotationZ = -5f * sin(p * Math.PI).toFloat() }
        .shadow((18f * (1f - p) + 7f * p).dp, RoundedCornerShape(2.dp)).testTag("library_extraction_flight")) {
        coverContent(extraction.book)
    }
}

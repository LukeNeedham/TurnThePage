package com.avstaim.listai.feature.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.SemanticsPropertyKey
import com.avstaim.listai.core.model.LibraryBook
import com.avstaim.listai.core.model.LibraryCatalog
import kotlin.math.sin

/** Test telemetry does not become an accessibility announcement or visible product text. */
val LibraryStackDropProgressKey = SemanticsPropertyKey<Float>("LibraryStackDropProgress")
val LibraryStackShelfProgressKey = SemanticsPropertyKey<Float>("LibraryStackShelfProgress")

internal const val LIBRARY_HELD_BOOK_SCALE = 1.04f
internal const val LIBRARY_STACK_DROP_MILLIS = 560

/** Own the cover and the pre-commit shelf until both the flight and Room commit have finished. */
internal data class LibraryStackDrop(
    val book: LibraryBook,
    val target: LibraryTile,
    val from: Rect,
    val destination: Rect,
    val shelf: LibraryCatalog,
    val viewport: Rect = Rect.Zero,
    val releasingShelf: Boolean = false,
    val reflowTarget: Rect? = null,
) {
    val stackId: String get() = requireNotNull(target.stack).id
    fun isCommittedIn(catalog: LibraryCatalog): Boolean =
        catalog.books.any { it.id == book.id && it.stackId == stackId } &&
            catalog.stacks.any { it.id == stackId && it.bookIds.firstOrNull() == book.id }
}

internal data class StackDropFrame(
    val movingBook: StackBookGeometry,
    val supportingBooks: List<StackBookGeometry>,
    val previousTopBook: StackBookGeometry,
)

/** Pure geometry; its first frame is exactly the held cover and its last uses the RC6 binding. */
internal fun stackDropFrame(from: Rect, destination: Rect, count: Int, progress: Float): StackDropFrame {
    val p = progress.coerceIn(0f, 1f)
    val current = stackBookGeometry(destination.width, destination.height, count).map { it.translated(destination.topLeft) }
    val final = stackBookGeometry(destination.width, destination.height, count + 1).map { it.translated(destination.topLeft) }
    val landing = final.last()
    val held = from.inflate(from.width * (LIBRARY_HELD_BOOK_SCALE - 1f) / 2f,
        from.height * (LIBRARY_HELD_BOOK_SCALE - 1f) / 2f)
    val start = StackBookGeometry(held.topLeft, held.topRight, held.bottomRight, held.bottomLeft, 0f)
    // The incoming book stays above every existing lid for the whole flight. It settles
    // onto the top plane, never changes its drawing order or passes through a cover.
    val arc = Offset(0f, -destination.height * .12f * sin(p * Math.PI).toFloat())
    val moving = start.between(landing, p).translated(arc)
    val supports = final.dropLast(1)
    // Four is the visual cap. For a larger stack the lower books compress beneath the
    // new top; overlapping the two lowest bindings keeps that reduction occluded.
    val shift = (current.size - supports.size).coerceAtLeast(0)
    val compression = ((p - .45f) / .55f).coerceIn(0f, 1f)
    val settledSupports = current.mapIndexed { index, book ->
        book.between(supports[(index - shift).coerceAtLeast(0)], compression)
    }
    return StackDropFrame(moving, settledSupports.dropLast(1), settledSupports.last())
}

internal fun stackDropDestination(from: Rect, to: Rect, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    return Rect(from.topLeft + (to.topLeft - from.topLeft) * p,
        androidx.compose.ui.geometry.Size(from.width + (to.width - from.width) * p, from.height + (to.height - from.height) * p))
}

private fun Rect.inflate(x: Float, y: Float) = Rect(left - x, top - y, right + x, bottom + y)
private fun StackBookGeometry.translated(delta: Offset) = copy(backLeft = backLeft + delta,
    backRight = backRight + delta, frontRight = frontRight + delta, frontLeft = frontLeft + delta)
private fun StackBookGeometry.between(other: StackBookGeometry, p: Float) = StackBookGeometry(
    backLeft + (other.backLeft - backLeft) * p, backRight + (other.backRight - backRight) * p,
    frontRight + (other.frontRight - frontRight) * p, frontLeft + (other.frontLeft - frontLeft) * p,
    thickness + (other.thickness - thickness) * p,
)
/** No artwork decode or layout per frame: the same measured cover is projected as a rigid lid. */
@Composable
internal fun LibraryStackDropFlight(
    drop: LibraryStackDrop,
    progress: Float,
    rootOrigin: Offset,
    coverContent: @Composable (LibraryBook) -> Unit,
    destination: Rect = drop.destination,
    shelfProgress: Float = 0f,
) {
    val frame = stackDropFrame(drop.from.translate(-rootOrigin), destination.translate(-rootOrigin),
        requireNotNull(drop.target.stack).bookIds.size, progress)
    val density = LocalDensity.current
    val width = with(density) { drop.from.width.toDp() }
    val height = with(density) { drop.from.height.toDp() }
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Box(Modifier.size(width, height).testTag("library_stack_drop_flight").semantics {
        this[LibraryStackDropProgressKey] = progress
        this[LibraryStackShelfProgressKey] = shelfProgress
    }) {
        Canvas(Modifier.size(width, height)) { frame.supportingBooks.forEach { drawStackBook(it, dark, false) } }
        drop.target.book?.let { top ->
            StackDropProjectedBook(top, frame.previousTopBook, dark, coverContent, top = false)
        }
        // A stable topmost layer keeps the complete arriving cover visible. Room uses the
        // same first member, so the final rigid lid and the settled shelf have one identity.
        StackDropProjectedBook(drop.book, frame.movingBook, dark, coverContent)
    }
}

@Composable
private fun StackDropProjectedBook(
    book: LibraryBook,
    geometry: StackBookGeometry,
    dark: Boolean,
    coverContent: @Composable (LibraryBook) -> Unit,
    top: Boolean = true,
) {
    Box(Modifier.fillMaxSize().drawWithCache {
        val source = floatArrayOf(0f, 0f, size.width, 0f, size.width, size.height, 0f, size.height)
        val target = floatArrayOf(geometry.backLeft.x, geometry.backLeft.y, geometry.backRight.x, geometry.backRight.y,
            geometry.frontRight.x, geometry.frontRight.y, geometry.frontLeft.x, geometry.frontLeft.y)
        val projection = android.graphics.Matrix().apply { setPolyToPoly(source, 0, target, 0, 4) }
        onDrawWithContent {
            if (geometry.thickness > .01f) drawStackBook(geometry, dark, top)
            drawIntoCanvas { canvas ->
                canvas.save(); canvas.nativeCanvas.concat(projection)
                this@onDrawWithContent.drawContent()
                canvas.restore()
            }
            if (top && geometry.thickness > .01f) drawStackLidRelief(geometry)
        }
    }) { coverContent(book) }
}

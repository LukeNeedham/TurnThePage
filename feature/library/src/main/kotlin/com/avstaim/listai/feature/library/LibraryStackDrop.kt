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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
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
            catalog.stacks.any { it.id == stackId && book.id in it.bookIds }
}

internal data class StackDropFrame(
    val movingBook: StackBookGeometry,
    val supportingBooks: List<StackBookGeometry>,
    val topBook: StackBookGeometry,
    val tuckedFraction: Float,
)

/** Pure geometry; its first frame is exactly the held cover and its last uses the RC6 binding. */
internal fun stackDropFrame(from: Rect, destination: Rect, count: Int, progress: Float): StackDropFrame {
    val p = progress.coerceIn(0f, 1f)
    val current = stackBookGeometry(destination.width, destination.height, count).map { it.translated(destination.topLeft) }
    val final = stackBookGeometry(destination.width, destination.height, count + 1).map { it.translated(destination.topLeft) }
    val landing = final[final.lastIndex - 1]
    val held = from.inflate(from.width * (LIBRARY_HELD_BOOK_SCALE - 1f) / 2f,
        from.height * (LIBRARY_HELD_BOOK_SCALE - 1f) / 2f)
    val start = StackBookGeometry(held.topLeft, held.topRight, held.bottomRight, held.bottomLeft, 0f)
    // A shallow lifting arc settles onto the exposed fore-edge beneath the retained top book.
    val arc = Offset(0f, -destination.height * .09f * sin(p * Math.PI).toFloat())
    val moving = start.between(landing, p).translated(arc)
    val lift = (p / .72f).coerceIn(0f, 1f)
    val top = current.last().between(final.last(), lift)
    return StackDropFrame(moving, current.dropLast(1), top, ((p - .48f) / .52f).coerceIn(0f, 1f))
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
private fun StackBookGeometry.backPortion(fraction: Float) = Path().apply {
    // Include the upper book's fore-edge too: its page block physically hides the
    // incoming printed lid. Stopping at the lid would leave a visible artwork strip.
    val down = Offset(0f, thickness)
    val left = backLeft + (frontLeft + down - backLeft) * fraction
    val right = backRight + (frontRight + down - backRight) * fraction
    moveTo(backLeft.x, backLeft.y); lineTo(backRight.x, backRight.y)
    lineTo(right.x, right.y); lineTo(left.x, left.y); close()
}

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
        // Keep the existing first member as the stack's cover. AddToStack appends membership;
        // the incoming book slides under this lid, rather than changing the order for animation.
        drop.target.book?.let { top ->
            StackDropProjectedBook(top, frame.topBook, dark, coverContent)
        }
        StackDropProjectedBook(drop.book, frame.movingBook, dark, coverContent, top = false)
        if (frame.tuckedFraction > 0f) drop.target.book?.let { top ->
            StackDropProjectedBook(top, frame.topBook, dark, coverContent,
                occlusion = frame.topBook.backPortion(frame.tuckedFraction))
        }
    }
}

@Composable
private fun StackDropProjectedBook(
    book: LibraryBook,
    geometry: StackBookGeometry,
    dark: Boolean,
    coverContent: @Composable (LibraryBook) -> Unit,
    occlusion: Path? = null,
    top: Boolean = true,
) {
    Box(Modifier.fillMaxSize().drawWithCache {
        val source = floatArrayOf(0f, 0f, size.width, 0f, size.width, size.height, 0f, size.height)
        val target = floatArrayOf(geometry.backLeft.x, geometry.backLeft.y, geometry.backRight.x, geometry.backRight.y,
            geometry.frontRight.x, geometry.frontRight.y, geometry.frontLeft.x, geometry.frontLeft.y)
        val projection = android.graphics.Matrix().apply { setPolyToPoly(source, 0, target, 0, 4) }
        onDrawWithContent {
            fun drawBook() {
                if (geometry.thickness > .01f) drawStackBook(geometry, dark, top)
                drawIntoCanvas { canvas ->
                    canvas.save(); canvas.nativeCanvas.concat(projection)
                    this@onDrawWithContent.drawContent()
                    canvas.restore()
                }
                if (top && geometry.thickness > .01f) drawStackLidRelief(geometry)
            }
            if (occlusion == null) drawBook() else clipPath(occlusion) { drawBook() }
        }
    }) { coverContent(book) }
}

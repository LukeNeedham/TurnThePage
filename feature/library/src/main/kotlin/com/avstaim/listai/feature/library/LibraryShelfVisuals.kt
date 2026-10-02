package com.avstaim.listai.feature.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.model.LibraryBook

/** Cabinet light belongs to the library; reading paper keeps its own selected theme. */
internal fun DrawScope.drawLibraryCabinet(dark: Boolean) {
    val edge = if (dark) Color(0xFF102333) else Color(0xFF203C50)
    val centre = if (dark) Color(0xFF294657) else Color(0xFF527389)
    drawRect(Brush.horizontalGradient(listOf(edge, centre, centre, edge), 0f, size.width))
    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .16f), Color.Transparent,
        Color.Black.copy(alpha = .09f))))
    drawRect(Brush.radialGradient(listOf(Color(0xFFBED1DD).copy(alpha = if (dark) .05f else .13f),
        Color.Transparent), center = Offset(size.width * .48f, size.height * .32f), radius = size.maxDimension * .7f))
}

/** A light, thin front edge supports the books against the shaded cabinet recess. */
@Composable
internal fun LibraryShelfBoard() {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF102536).copy(alpha = .48f)), -8.dp.toPx(), 0f),
            topLeft = Offset(0f, -8.dp.toPx()), size = Size(size.width, 8.dp.toPx()))
        drawRect(Brush.verticalGradient(listOf(Color(0xFFE2EDF3), Color(0xFF9FBCCC), Color(0xFF7898AD)), 0f, h))
        drawLine(Color.White.copy(alpha = if (dark) .65f else .9f), Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
        drawRect(Brush.verticalGradient(listOf(Color(0xFF091D2B).copy(alpha = .32f), Color.Transparent), h, h + 13.dp.toPx()),
            topLeft = Offset(0f, h), size = Size(size.width, 13.dp.toPx()))
    }
}

/** All faces use the same projected corners, so the printed lid is part of a bound book. */
@Composable
internal fun LibraryStackCover(tile: LibraryTile, coverContent: @Composable (LibraryBook) -> Unit) {
    val stack = tile.stack ?: return
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Box(Modifier.fillMaxSize().semantics { contentDescription = "${stack.name}, ${stack.bookIds.size}" }
        .drawWithCache {
            val books = stackBookGeometry(size.width, size.height, stack.bookIds.size)
            val lid = books.last()
            val source = floatArrayOf(0f, 0f, size.width, 0f, size.width, size.height, 0f, size.height)
            val target = floatArrayOf(lid.backLeft.x, lid.backLeft.y, lid.backRight.x, lid.backRight.y,
                lid.frontRight.x, lid.frontRight.y, lid.frontLeft.x, lid.frontLeft.y)
            val projection = android.graphics.Matrix().apply { setPolyToPoly(source, 0, target, 0, 4) }
            onDrawWithContent {
                books.forEachIndexed { index, book -> drawStackBook(book, dark, index == books.lastIndex) }
                // The artwork stays in its native upright coordinate system until this one
                // projection. It is neither cropped into a trapezoid nor laid over another lid.
                drawIntoCanvas { canvas ->
                    canvas.save()
                    canvas.nativeCanvas.concat(projection)
                    this@onDrawWithContent.drawContent()
                    canvas.restore()
                }
                drawStackLidRelief(lid)
            }
        }) { tile.book?.let { coverContent(it) } }
}

internal data class ShelfCaption(val title: String, val author: String, val separateCount: Boolean = false) {
    val label: String get() = "$title · $author"
}

/** Only stacks need a small paper nameplate; a book already carries its own title. */
@Composable
internal fun LibraryShelfCaption(caption: ShelfCaption, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color(0xFFF3F3F0), contentColor = Color(0xFF345168),
        shape = RoundedCornerShape(3.dp), shadowElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (caption.separateCount) caption.title else caption.label, style = MaterialTheme.typography.labelSmall,
                maxLines = if (caption.separateCount) 2 else 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (caption.separateCount) Text(caption.author, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

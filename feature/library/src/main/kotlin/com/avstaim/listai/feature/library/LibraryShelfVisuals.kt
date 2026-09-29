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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
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

/** Covers lie flat: dark bindings surround fine paper edges and the lid recedes in perspective. */
@Composable
internal fun LibraryStackCover(tile: LibraryTile, coverContent: @Composable (LibraryBook) -> Unit) {
    val stack = tile.stack ?: return
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    BoxWithConstraints(Modifier.fillMaxSize().semantics { contentDescription = "${stack.name}, ${stack.bookIds.size}" }) {
        val layerHeight = maxHeight * .078f
        val count = minOf(stack.bookIds.size, 4).coerceAtLeast(1)
        val bindings = listOf(Color(0xFF27485E), Color(0xFF34586C), Color(0xFF203D53), Color(0xFF31536A))
        repeat(count) { index ->
            Canvas(Modifier.align(Alignment.BottomCenter).width(maxWidth * (.91f + .015f * (index % 2))).height(layerHeight)
                .offset(x = if (index % 2 == 0) (-2).dp else 2.dp, y = -(layerHeight * index))
                .shadow(2.dp, RoundedCornerShape(1.dp))) {
                drawRect(bindings[index])
                val edge = if (dark) Color(0xFFC4C9CA) else Color(0xFFEEF0EF)
                drawRect(Brush.verticalGradient(listOf(edge, edge.copy(alpha = .9f))),
                    Offset(4.dp.toPx(), size.height * .12f), Size(size.width - 7.dp.toPx(), size.height * .72f))
                repeat(3) { line ->
                    val y = size.height * (.3f + line * .16f)
                    drawLine(Color(0xFF83939D).copy(alpha = .45f), Offset(5.dp.toPx(), y), Offset(size.width - 4.dp.toPx(), y), .4.dp.toPx())
                }
            }
        }
        tile.book?.let { book ->
            Box(Modifier.width(maxWidth * .94f).height(maxHeight * .86f).align(Alignment.BottomCenter)
                .offset(y = -(layerHeight * count))
                .graphicsLayer { transformOrigin = TransformOrigin(.5f, 1f); rotationX = 60f; rotationZ = -1f; cameraDistance = 24f * density }
                .shadow(7.dp, RoundedCornerShape(2.dp))) { coverContent(book) }
        }
    }
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

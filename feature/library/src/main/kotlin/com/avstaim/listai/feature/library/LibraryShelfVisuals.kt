package com.avstaim.listai.feature.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.avstaim.listai.core.model.LibraryBook

/** A narrow bevel and shaded front face make the book baseline a physical support. */
@Composable
internal fun LibraryShelfBoard() {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val top = if (dark) Color(0xFF465463) else Color(0xFFDEE7ED)
    val front = if (dark) Color(0xFF2E3D4B) else Color(0xFFAFBFCB)
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF142735).copy(alpha = .24f)), -7.dp.toPx(), 0f),
            topLeft = Offset(0f, -7.dp.toPx()), size = Size(size.width, 7.dp.toPx()))
        drawRect(Brush.verticalGradient(listOf(top, top.copy(alpha = .85f)), 0f, h * .3f), size = Size(size.width, h * .3f))
        drawLine(if (dark) Color(0xFF647380) else Color.White.copy(alpha = .88f), Offset(0f, h * .3f), Offset(size.width, h * .3f), 1.dp.toPx())
        drawRect(Brush.verticalGradient(listOf(front, front.copy(alpha = .75f)), h * .35f, h),
            topLeft = Offset(0f, h * .35f), size = Size(size.width, h * .65f))
        drawLine(Color(0xFF112638).copy(alpha = .2f), Offset(0f, h), Offset(size.width, h), 1.dp.toPx())
    }
}

/** Covers lie flat: dark bindings surround fine paper edges and the lid recedes in perspective. */
@Composable
internal fun LibraryStackCover(tile: LibraryTile, coverContent: @Composable (LibraryBook) -> Unit) {
    val stack = tile.stack ?: return
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    BoxWithConstraints(Modifier.fillMaxSize().semantics { contentDescription = "${stack.name}, ${stack.bookIds.size}" }) {
        val layerHeight = maxHeight * .052f
        val count = minOf(stack.bookIds.size, 4).coerceAtLeast(1)
        val bindings = listOf(Color(0xFF3E5369), Color(0xFF6D7C88), Color(0xFF34485D), Color(0xFF566776))
        repeat(count) { index ->
            Canvas(Modifier.align(Alignment.BottomCenter).width(maxWidth * (.91f + .015f * (index % 2))).height(layerHeight)
                .offset(x = if (index % 2 == 0) (-2).dp else 2.dp, y = -(layerHeight * index))
                .shadow(2.dp, RoundedCornerShape(1.dp))) {
                drawRect(bindings[index])
                val edge = if (dark) Color(0xFFC4C9CA) else Color(0xFFEEF0EF)
                drawRect(Brush.verticalGradient(listOf(edge, edge.copy(alpha = .9f))),
                    Offset(4.dp.toPx(), size.height * .18f), Size(size.width - 7.dp.toPx(), size.height * .65f))
                repeat(3) { line ->
                    val y = size.height * (.3f + line * .16f)
                    drawLine(Color(0xFF83939D).copy(alpha = .45f), Offset(5.dp.toPx(), y), Offset(size.width - 4.dp.toPx(), y), .4.dp.toPx())
                }
            }
        }
        tile.book?.let { book ->
            Box(Modifier.width(maxWidth * .94f).height(maxHeight * .86f).align(Alignment.BottomCenter)
                .offset(y = -(layerHeight * count))
                .graphicsLayer { transformOrigin = TransformOrigin(.5f, 1f); rotationX = 64f; rotationZ = -2f; cameraDistance = 24f * density }
                .shadow(7.dp, RoundedCornerShape(2.dp))) { coverContent(book) }
        }
    }
}

internal data class ShelfCaption(val title: String, val author: String, val membership: String = "", val stack: Boolean = false)

/** Paper labels belong to the shelf fascia, never between a cover and its supporting edge. */
@Composable
internal fun LibraryShelfCaption(caption: ShelfCaption, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, color = colors.surface.copy(alpha = if (caption.stack) .97f else .84f),
        shape = RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp), shadowElevation = if (caption.stack) 1.dp else 0.dp) {
        Column(Modifier.padding(horizontal = 7.dp, vertical = 5.dp)) {
            Text(caption.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurface)
            if (caption.author.isNotBlank()) Text(caption.author, style = MaterialTheme.typography.labelSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            if (caption.membership.isNotBlank()) Text(caption.membership, style = MaterialTheme.typography.labelSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

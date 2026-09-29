package com.avstaim.listai.feature.library

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Projected corners of one rigid lid; every exposed face is extruded from these edges. */
internal data class StackBookGeometry(
    val backLeft: Offset,
    val backRight: Offset,
    val frontRight: Offset,
    val frontLeft: Offset,
    val thickness: Float,
)

internal fun stackBookGeometry(width: Float, height: Float, count: Int): List<StackBookGeometry> {
    val visibleCount = count.coerceIn(1, 4)
    val thickness = height * .071f
    val depth = minOf(width * .72f, height * .47f)
    return List(visibleCount) { index ->
        // Small offsets belong to the whole book, not independently to its lid and pages.
        val shift = if (index % 2 == 0) -.008f * width else .008f * width
        val front = height - thickness * (index + 1)
        StackBookGeometry(
            backLeft = Offset(width * .135f + shift, front - depth),
            backRight = Offset(width * .885f + shift, front - depth),
            frontRight = Offset(width * .975f + shift, front),
            frontLeft = Offset(width * .025f + shift, front),
            thickness = thickness,
        )
    }
}

private fun polygon(a: Offset, b: Offset, c: Offset, d: Offset) = Path().apply {
    moveTo(a.x, a.y)
    lineTo(b.x, b.y)
    lineTo(c.x, c.y)
    lineTo(d.x, d.y)
    close()
}

internal fun DrawScope.drawStackBook(book: StackBookGeometry, dark: Boolean, top: Boolean) {
    val (backLeft, backRight, frontRight, frontLeft, thickness) = book
    val down = Offset(0f, thickness)
    val board = maxOf(thickness * .12f, .7.dp.toPx())
    val stock = if (top) Color(0xFF293A44) else Color(0xFF294B60)

    // Close contact at the base of each book; never an elevated Android shadow for the lid.
    drawPath(polygon(backLeft + down, backRight + down, frontRight + down, frontLeft + down),
        Color.Black.copy(alpha = .3f), style = Stroke(2.dp.toPx()))
    drawPath(polygon(backLeft, backRight, frontRight, frontLeft), stock)
    // Rounded binding at the left is the visible spine; no paper lines cross this face.
    drawPath(polygon(backLeft, frontLeft, frontLeft + down, backLeft + down),
        Brush.horizontalGradient(listOf(Color(0xFF152C3B), stock, Color(0xFF152C3B)),
            frontLeft.x, backLeft.x + (frontRight.x - frontLeft.x) * .015f))
    drawPath(polygon(frontLeft, frontRight, frontRight + down, frontLeft + down), stock)

    val inset = (frontRight.x - frontLeft.x) * .023f
    val paperLeft = frontLeft + Offset(inset, board * 1.7f)
    val paperRight = frontRight + Offset(-inset * .6f, board * 1.7f)
    val paperBottom = frontLeft.y + thickness - board * 1.35f
    val page = if (dark) Color(0xFFC3C8C9) else Color(0xFFEBEEE9)
    drawPath(polygon(paperLeft, paperRight, Offset(paperRight.x - board * .2f, paperBottom),
        Offset(paperLeft.x + board * .3f, paperBottom)),
        Brush.verticalGradient(listOf(Color(0xFF82949D), page, page, Color(0xFFBBC4C6)),
            paperLeft.y, paperBottom))
    repeat(4) { line ->
        val y = paperLeft.y + (paperBottom - paperLeft.y) * (line + 1) / 5f
        drawLine(Color(0xFF637985).copy(alpha = if (line % 2 == 0) .28f else .16f),
            Offset(paperLeft.x + board * .3f, y), Offset(paperRight.x - board * .4f, y), .35.dp.toPx())
    }

    // The upper board has a real cut edge. Its highlight meets the projected artwork exactly.
    drawPath(polygon(frontLeft, frontRight, frontRight + Offset(0f, board), frontLeft + Offset(0f, board)),
        Brush.verticalGradient(listOf(Color(0xFF627580), stock), frontLeft.y, frontLeft.y + board))
    drawLine(Color(0xFF76909D).copy(alpha = .55f), frontLeft + down, frontRight + down, .6.dp.toPx())
}

internal fun DrawScope.drawStackLidRelief(book: StackBookGeometry) {
    val (backLeft, backRight, frontRight, frontLeft) = book
    // Light grazes the board edge; the hinge remains on the printed face, like an actual jacket.
    drawPath(polygon(backLeft, backRight, frontRight, frontLeft), Color(0xFF08131C).copy(alpha = .35f),
        style = Stroke(.65.dp.toPx()))
    drawLine(Color.White.copy(alpha = .38f), frontLeft, frontRight, .7.dp.toPx())
    val hingeTop = backLeft + (backRight - backLeft) * .035f
    val hingeBottom = frontLeft + (frontRight - frontLeft) * .035f
    drawLine(Color.Black.copy(alpha = .2f), hingeTop, hingeBottom, 1.dp.toPx())
    drawLine(Color.White.copy(alpha = .12f), hingeTop + Offset(1.dp.toPx(), 0f),
        hingeBottom + Offset(1.dp.toPx(), 0f), .55.dp.toPx())
}

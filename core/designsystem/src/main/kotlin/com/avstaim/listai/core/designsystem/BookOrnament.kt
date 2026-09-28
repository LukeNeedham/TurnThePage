package com.avstaim.listai.core.designsystem

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Resolution-independent book flourish, painted at the target page's native resolution. */
class BookOrnament(color: Int) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.25f
    }
    private val path = Path().apply {
        moveTo(0f, 12f); lineTo(28f, 12f)
        moveTo(72f, 12f); lineTo(100f, 12f)
        for (center in listOf(36f, 45f, 54f, 63f)) {
            moveTo(center - 5f, 12f)
            cubicTo(center - 5f, 3f, center + 6f, 3f, center + 6f, 12f)
            cubicTo(center + 6f, 21f, center - 5f, 21f, center - 5f, 12f)
        }
    }

    fun draw(canvas: Canvas, bounds: RectF) {
        val save = canvas.save()
        canvas.translate(bounds.left, bounds.top)
        canvas.scale(bounds.width() / 100f, bounds.height() / 24f)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }
}

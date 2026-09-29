package com.avstaim.listai.feature.library

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Debug-only high-contrast print shared by preview and real GPU regression captures. */
fun brightPrintedStackFixture(): ImageBitmap {
    val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
        drawColor(0xFFFFF9EC.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEFC848.toInt() }
        drawCircle(200f, 312f, 116f, paint)
        paint.color = 0xFF2E657C.toInt()
        drawRect(0f, 318f, 400f, 405f, paint)
        paint.color = 0xFFE8EDE7.toInt()
        repeat(4) { drawLine(0f, 332f + it * 17f, 400f, 332f + it * 17f, paint) }
        paint.color = 0xFF152D42.toInt()
        paint.typeface = Typeface.create("serif", Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 39f
        drawText("СВЕТ", 200f, 85f, paint)
        drawText("НАД МОРЕМ", 200f, 136f, paint)
        paint.typeface = Typeface.create("serif", Typeface.NORMAL)
        paint.textSize = 22f
        drawText("АЛЕКСАНДР СЕВЕРНЫЙ", 200f, 556f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        drawRect(5f, 5f, 395f, 595f, paint)
    }
    return bitmap.asImageBitmap()
}


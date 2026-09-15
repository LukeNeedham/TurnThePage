package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/** One continuous cool surround reaches behind the camera inset and the physical book. */
internal fun DrawScope.drawReaderBackdrop(darkTheme: Boolean) {
    drawRect(Brush.radialGradient(
        colors = if (darkTheme) listOf(Color(0xFF263440), Color(0xFF141E28))
            else listOf(Color(0xFF70879A), Color(0xFF435A6D)),
        center = Offset(size.width * .48f, size.height * .42f),
        radius = size.maxDimension * .78f,
    ))
}

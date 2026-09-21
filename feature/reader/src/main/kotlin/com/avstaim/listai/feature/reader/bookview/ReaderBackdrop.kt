package com.avstaim.listai.feature.reader.bookview

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.avstaim.listai.core.designsystem.drawListaiBackdrop

/** One continuous cool surround reaches behind the camera inset and the physical book. */
internal fun DrawScope.drawReaderBackdrop(darkTheme: Boolean) {
    drawListaiBackdrop(darkTheme)
}

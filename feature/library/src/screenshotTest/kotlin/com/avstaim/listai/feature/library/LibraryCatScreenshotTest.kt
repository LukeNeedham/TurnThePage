package com.avstaim.listai.feature.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Library cat — light large", widthDp = 210, heightDp = 150)
@Composable
fun LibraryCatLightLargeScreenshot() { CatPreview(134f, false) }

@PreviewTest
@Preview(name = "Library cat — light medium", widthDp = 210, heightDp = 150)
@Composable
fun LibraryCatLightMediumScreenshot() { CatPreview(114f, false) }

@PreviewTest
@Preview(name = "Library cat — light small", widthDp = 210, heightDp = 150)
@Composable
fun LibraryCatLightSmallScreenshot() { CatPreview(94f, false) }

@PreviewTest
@Preview(name = "Library cat — dark large", widthDp = 210, heightDp = 150)
@Composable
fun LibraryCatDarkLargeScreenshot() { CatPreview(134f, true) }

@PreviewTest
@Preview(name = "Library cat — dark medium", widthDp = 210, heightDp = 150)
@Composable
fun LibraryCatDarkMediumScreenshot() { CatPreview(114f, true) }

@PreviewTest
@Preview(name = "Library cat — dark small", widthDp = 210, heightDp = 150)
@Composable
fun LibraryCatDarkSmallScreenshot() { CatPreview(94f, true) }

@PreviewTest
@Preview(name = "Library cat — sleepy reaction", widthDp = 210, heightDp = 150)
@Composable
fun LibraryCatReactionScreenshot() { CatPreview(134f, false, reacting = true) }

@Composable
private fun CatPreview(width: Float, dark: Boolean, reacting: Boolean = false) {
    val background = if (dark) Color(0xFF151D29) else Color(0xFFE4EAF0)
    val shelf = if (dark) Color(0xFF394757) else Color(0xFFA5B5C4)
    val book = if (dark) Color(0xFF3B536A) else Color(0xFF426B88)
    val top = 72f - width * 150f / 256f * .68f
    Box(Modifier.fillMaxSize().background(background)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(shelf, Offset(0f, 113.dp.toPx()), Size(size.width, 5.dp.toPx()))
            drawRect(book, Offset(32.dp.toPx(), 72.dp.toPx()), Size((width * .78f).dp.toPx(), 12.dp.toPx()))
            drawRect(shelf, Offset(37.dp.toPx(), 85.dp.toPx()), Size((width * .76f).dp.toPx(), 10.dp.toPx()))
            drawRect(book.copy(alpha = .7f), Offset(30.dp.toPx(), 96.dp.toPx()), Size((width * .82f).dp.toPx(), 16.dp.toPx()))
        }
        LibraryCatArtwork(Modifier.offset(25.dp, top.dp).size(width.dp, (width * 150f / 256f).dp),
            darkTheme = dark, eyeOpening = if (reacting) 1f else 0f, tailSwing = if (reacting) .65f else 0f)
    }
}

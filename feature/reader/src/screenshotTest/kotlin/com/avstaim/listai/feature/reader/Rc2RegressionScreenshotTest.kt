package com.avstaim.listai.feature.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.adaptive.DevicePosture
import com.avstaim.listai.core.adaptive.FoldBounds
import com.avstaim.listai.core.adaptive.FoldOrientation
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookBlock
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.BookMetadata
import com.avstaim.listai.core.model.CoverPosition
import com.avstaim.listai.core.model.InlineNode
import com.avstaim.listai.core.model.ReadingProgress

@PreviewTest
@Preview(name = "RC2 — closed book on centered Fold", widthDp = 680, heightDp = 760, locale = "ru")
@Composable
fun Rc2ClosedBookOnFoldScreenshot() {
    val density = LocalDensity.current
    val posture = remember(density) {
        val width = with(density) { 680.dp.roundToPx() }
        val height = with(density) { 760.dp.roundToPx() }
        DevicePosture.Flat(
            foldBounds = FoldBounds(width / 2 - 1, 0, width / 2 + 1, height),
            orientation = FoldOrientation.VERTICAL,
            separating = true,
            occluding = true,
        )
    }
    val document = remember {
        val body = BookBlock.Paragraph(BlockId("rc2-body"), listOf(InlineNode.Text(
            "Книга сохраняет настоящий разворот и не растягивает закрытую обложку на весь экран. ".repeat(20))))
        BookDocument(BookId("rc2-fold-cover"),
            BookMetadata("Атлас тихих страниц", authors = listOf("Мастерская открытой книги"),
                language = "ru", annotation = "Описание настоящей бумажной книги."),
            listOf(body))
    }
    ListaiTheme(darkTheme = false) {
        ReaderScreen(
            document = document,
            modifier = Modifier.fillMaxSize(),
            devicePosture = posture,
            // Preview rendering does not publish window-space layout coordinates reliably;
            // force the already-verified Fold presentation so this golden guards one native
            // leaf plus the vertical controls, rather than silently falling back to SINGLE.
            presentationMode = ReaderPresentationMode.SPREAD,
            initialProgress = ReadingProgress(BookLocation(BlockId("rc2-body"), 0, 0), CoverPosition.FRONT),
            onboardingCompleted = true,
        )
    }
}

@PreviewTest
@Preview(name = "RC2 — closed book menu on folded screen", widthDp = 412, heightDp = 850, locale = "ru")
@Composable
fun Rc2ClosedBookMenuOnFoldedScreenScreenshot() {
    val document = remember {
        val body = BookBlock.Paragraph(BlockId("rc2-single-body"), listOf(InlineNode.Text(
            "Первая глава начинается только после осознанного открытия книги. ".repeat(20))))
        BookDocument(BookId("rc2-folded-cover-menu"),
            BookMetadata("Атлас тихих страниц", authors = listOf("Мастерская открытой книги"),
                language = "ru", annotation = "Описание настоящей бумажной книги."),
            listOf(body))
    }
    ListaiTheme(darkTheme = false) {
        ReaderScreen(
            document = document,
            modifier = Modifier.fillMaxSize(),
            presentationMode = ReaderPresentationMode.SINGLE,
            initialProgress = ReadingProgress(BookLocation(BlockId("rc2-single-body"), 0, 0),
                CoverPosition.FRONT),
            onboardingCompleted = true,
        )
    }
}

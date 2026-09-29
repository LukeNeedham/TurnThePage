package com.avstaim.listai.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.bookview.PhysicalBookView

@PreviewTest
@Preview(name = "Epilogue — phone white", widthDp = 412, heightDp = 800, locale = "ru")
@Composable
fun EpiloguePhoneScreenshot() { EpiloguePreview() }

@PreviewTest
@Preview(name = "Epilogue — phone graphite", widthDp = 412, heightDp = 800, locale = "ru")
@Composable
fun EpilogueDarkPhoneScreenshot() { EpiloguePreview(dark = true) }

@PreviewTest
@Preview(name = "Epilogue — Fold white after a part", widthDp = 1000, heightDp = 760, locale = "ru")
@Composable
fun EpilogueFoldScreenshot() { EpiloguePreview(withPart = true) }

@PreviewTest
@Preview(name = "Epilogue — Fold graphite after a part", widthDp = 1000, heightDp = 760, locale = "ru")
@Composable
fun EpilogueDarkFoldScreenshot() { EpiloguePreview(dark = true, withPart = true) }

@PreviewTest
@Preview(name = "Epilogue — narrow phone large text", widthDp = 360, heightDp = 760, locale = "ru", fontScale = 1.5f)
@Composable
fun EpilogueLargeTextScreenshot() { EpiloguePreview() }

@PreviewTest
@Preview(name = "Epilogue — English source heading only", widthDp = 412, heightDp = 800, locale = "en")
@Composable
fun EpilogueEnglishScreenshot() { EpiloguePreview(english = true) }

@Composable
private fun EpiloguePreview(dark: Boolean = false, withPart: Boolean = false, english: Boolean = false) {
    val document = remember(withPart, english) {
        fun text(id: String, value: String) = BookBlock.Paragraph(BlockId(id), listOf(InlineNode.Text(value)))
        fun heading(id: String, value: String) = BookBlock.Heading(BlockId(id), 1, listOf(InlineNode.Text(value)))
        val epilogue = heading("epilogue", if (english) "Epilogue" else "Эпилог")
        val title = heading("title", "Дом для света")
        BookDocument(BookId("epilogue-preview"), BookMetadata("Седьмой огонь", listOf("Александр Ладожский"), if (english) "en" else "ru"),
            (if (withPart) listOf(heading("part", "Часть вторая")) else emptyList()) + listOf(
                text("previous", "В нижнем углу Лида написала: «Состояние на сентябрь 2026 года». У каждой карты должны быть свидетели."),
                epilogue,
            ) + (if (english) emptyList() else listOf(title)) + listOf(text("body", if (english)
                "In spring, Lida returned to the island. Ice still held in the bays, but beyond the headland the water was open. The lighthouse had become a home for the light. ".repeat(7)
            else "Весной Лида вернулась на Тонкий. Лёд ещё держался в бухтах, но над Сухим Камнем уже ходила открытая вода. На двери маячного дома появилась синяя табличка: «Навигационная станция и архив северных маршрутов». ".repeat(7))),
            parts = if (withPart) listOf(BookPart(listOf(BlockId("part")))) else emptyList(),
            chapters = listOf(BookChapter(if (english) listOf(epilogue.id) else listOf(epilogue.id, title.id),
                null, kind = BookChapterKind.EPILOGUE)))
    }
    ListaiTheme(darkTheme = dark) {
        PhysicalBookView(emptyList(), document.metadata.title, document.metadata.authors.single(),
            document = document, darkTheme = dark, initialLocation = BookLocation(BlockId("epilogue"), 0, 0))
    }
}

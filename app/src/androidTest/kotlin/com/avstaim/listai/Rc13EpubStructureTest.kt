package com.avstaim.listai

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.*
import com.avstaim.listai.format.epub.EpubBookParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Styled EPUB paragraphs become real physical divisions through navigation semantics. */
@RunWith(AndroidJUnit4::class)
class Rc13EpubStructureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val progress = AtomicReference(ReadingProgress())

    @Test fun navigationParagraphsProduceRectoPartAndBlankVersoBeforeChapter() {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        val document = runBlocking { EpubBookParser().parse(fixture().inputStream(), "structure.epub") }
        assertEquals(1, document.parts.size)
        assertEquals(1, document.chapters.size)
        val part = document.parts.single().startBlockId
        val chapter = document.chapters.single().startBlockId
        val initial = ReadingProgress(location = document.locationsBySourceId.values.first { it.blockId == part && it.charOffset == 0 })
        progress.set(initial)
        compose.runOnUiThread { compose.activity.setContent {
            ListaiTheme {
                ReaderScreen(document, presentationMode = ReaderPresentationMode.SPREAD,
                    initialProgress = initial, onProgressChanged = progress::set, onboardingCompleted = true)
            }
        } }
        awaitText("Часть первая")
        assertEquals("PART_TITLE", config()[ReaderPaperKinds].last())
        assertEquals(part, progress.get().location?.blockId)
        assertFalse(text().last().contains("Глава первая"))
        capture("spread-epub-part")
        turn(true)
        awaitText("Глава первая")
        assertEquals(listOf("BLANK", "TEXT"), config()[ReaderPaperKinds])
        assertTrue(text().first().isBlank())
        assertEquals(chapter, progress.get().location?.blockId)
        capture("spread-epub-blank-chapter")
        turn(false)
        awaitText("Часть первая")
        assertEquals("PART_TITLE", config()[ReaderPaperKinds].last())
        compose.waitUntil(5_000) { progress.get().location == initial.location }
        assertEquals(initial.location, progress.get().location)
        capture("spread-epub-part-return")
    }

    private fun config() = compose.onNodeWithTag("book_view_demo").fetchSemanticsNode().config
    private fun text() = config()[SemanticsProperties.Text].map { it.text }
    private fun resting() = compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().singleOrNull()?.config?.let {
        it.getOrElse(ReaderPaginationComplete) { false } && !it.getOrElse(ReaderJumpInProgress) { true } &&
            it.getOrElse(ReaderTurnProgress) { 1f } == 0f
    } == true
    private fun awaitText(value: String) = compose.waitUntil(45_000) { resting() && text().any { it.contains(value) } }
    private fun turn(forward: Boolean) {
        compose.onNodeWithTag("book_view_demo").performTouchInput {
            swipe(Offset(width * if (forward) .92f else .08f, height * .68f),
                Offset(width * if (forward) .08f else .92f, height * .68f), durationMillis = 400)
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(200, 5_000)
        SystemClock.sleep(500)
        val bitmap = requireNotNull(automation.takeScreenshot())
        val directory = requireNotNull(compose.activity.getExternalFilesDir("rc13-epub")).also { it.mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "$name.txt").writeText("anchor=${progress.get().location}\n" + text().joinToString("\n---\n"))
        bitmap.recycle()
    }
    private fun fixture(): ByteArray {
        fun xhtml(body: String) = "<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><head><title>Книга</title></head><body>$body</body></html>"
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to "<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>",
            "OPS/book.opf" to "<package xmlns='http://www.idpf.org/2007/opf' version='3.0' unique-identifier='uid'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>Морская книга</dc:title><dc:identifier id='uid'>rc13</dc:identifier><dc:language>ru</dc:language></metadata><manifest><item id='part' href='part.xhtml' media-type='application/xhtml+xml'/><item id='chapter' href='chapter.xhtml' media-type='application/xhtml+xml'/><item id='nav' href='nav.xhtml' properties='nav' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='part'/><itemref idref='chapter'/></spine></package>",
            "OPS/part.xhtml" to xhtml("<p>${"Начальная история о море. ".repeat(160)}</p><p id='part' style='font-weight:bold'>Часть первая</p>"),
            "OPS/chapter.xhtml" to xhtml("<p id='chapter'><strong>Глава первая</strong></p><p>${"Тихая вода хранит свет 😀. ".repeat(120)}</p>"),
            "OPS/nav.xhtml" to xhtml("<nav epub:type='toc'><ol><li><a href='part.xhtml#part'>Часть первая</a><ol><li><a href='chapter.xhtml#chapter'>Глава первая</a></li></ol></li></ol></nav>"),
        )
        return ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip -> entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry()
            } }
        }.toByteArray()
    }
}

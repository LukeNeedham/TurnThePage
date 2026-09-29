package com.avstaim.listai

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.*
import com.avstaim.listai.format.fb2.Fb2BookParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real FB2 regression plus EPUB import through the production repository and reader. */
@RunWith(AndroidJUnit4::class)
class Rc7EpilogueFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val progress = AtomicReference(ReadingProgress())
    private val mode = mutableStateOf(ReaderPresentationMode.SINGLE)
    private val preferences = mutableStateOf(ReaderPreferences())
    private var imported: BookId? = null
    private lateinit var document: BookDocument
    private lateinit var epilogue: BookLocation

    @After fun removeOnlyTheImportedEpilogueFixture() {
        imported?.let { id -> runBlocking {
            (compose.activity.application as ListaiApplication).controller.repository.delete(id)
        } }
    }

    @Test fun fb2PhoneTurnsIntoTheEpilogueAndItsTextContinuesWithoutABlankVerso() {
        loadFb2()
        show(ReaderPresentationMode.SINGLE, dark = false)
        assertOpening()
        assertEquals(listOf("TEXT"), config()[ReaderPaperKinds])
        assertFalse("The epilogue uses the ordinary chapter folio", config()[ReaderVisibleFooters].isBlank())
        val opening = visibleText()
        capture("fb2-phone-opening-light")
        turn(false)
        compose.waitUntil(30_000) { resting() && visibleText() != opening }
        assertTrue("The previous chapter cannot contain the epilogue heading", visibleText().none { it.contains("Эпилог") })
        turn(true)
        compose.waitUntil(30_000) { resting() && visibleText() == opening }
        assertOpening()
        turn(true)
        compose.waitUntil(30_000) { resting() && visibleText() != opening }
        assertTrue("Text continues on the reverse of the epilogue opening", visibleText().any(String::isNotBlank))
        assertEquals(listOf("TEXT"), config()[ReaderPaperKinds])
        capture("fb2-phone-continuation-light")
    }

    @Test fun fb2FoldUsesARectoOpeningAndTheSameSourceThroughContentsAndLargeTextReflow() {
        loadFb2()
        show(ReaderPresentationMode.SPREAD, dark = true)
        assertOpening()
        assertTrue("The epilogue follows the book's parts on the right", visibleText().last().startsWith("Эпилог"))
        val opening = visibleText()
        capture("fb2-fold-opening-dark")
        val identity = config()[ReaderLayoutIdentity]
        visitEpilogueThroughContents()
        assertEquals(opening, visibleText())
        assertEquals(identity, config()[ReaderLayoutIdentity])
        assertEquals(epilogue, progress.get().location)
        capture("fb2-fold-contents-return")
        compose.runOnIdle {
            mode.value = ReaderPresentationMode.SINGLE
            preferences.value = preferences.value.copy(fontSizeSp = 24f)
        }
        compose.waitUntil(30_000) { resting() && config()[ReaderLayoutIdentity] != identity }
        assertEquals(epilogue, progress.get().location)
        assertOpening()
        capture("fb2-single-large-type-dark")
        val singleIdentity = config()[ReaderLayoutIdentity]
        compose.runOnIdle { mode.value = ReaderPresentationMode.SPREAD }
        compose.waitUntil(30_000) { resting() && config()[ReaderLayoutIdentity] != singleIdentity }
        assertEquals(epilogue, progress.get().location)
        assertTrue(visibleText().last().startsWith("Эпилог"))
        assertEquals("TEXT", config()[ReaderPaperKinds].last())
    }

    @Test fun epubSemanticEpilogueKeepsItsTitleTogetherAndContentsUsesTheOriginalAnchor() {
        awaitController()
        val repository = (compose.activity.application as ListaiApplication).controller.repository
        val bytes = epubFixture()
        document = runBlocking {
            val id = repository.import("rc7-epilogue.epub") { bytes.inputStream() }
            imported = id
            repository.open(id).document
        }
        val opening = document.chapters.single { it.kind == BookChapterKind.EPILOGUE }
        assertNull(opening.ordinal)
        assertNull(opening.number)
        assertEquals(2, opening.headingBlockIds.size)
        epilogue = document.tableOfContents.single { it.title == "Эпилог" }.location
        show(ReaderPresentationMode.SINGLE, dark = true)
        assertOpening()
        assertEquals(listOf("TEXT"), config()[ReaderPaperKinds])
        val text = visibleText()
        capture("epub-phone-opening-dark")
        turn(false)
        compose.waitUntil(30_000) { resting() && visibleText() != text }
        assertTrue(visibleText().none { it.contains("Эпилог") })
        turn(true)
        compose.waitUntil(30_000) { resting() && visibleText() == text }
        visitEpilogueThroughContents()
        assertEquals(text, visibleText())
        assertEquals(epilogue, progress.get().location)
        capture("epub-phone-contents-return")
    }

    private fun loadFb2() {
        awaitController()
        document = runBlocking {
            compose.activity.resources.openRawResource(R.raw.stage4_reading_sample).use {
                Fb2BookParser().parse(it, "seventh-light.fb2")
            }
        }
        epilogue = document.locationsBySourceId.getValue("epilogue")
        val chapters = document.chapters.filter { it.kind == BookChapterKind.CHAPTER }
        assertEquals("The nine original chapters keep their ordinals", (1..9).toList(), chapters.map { it.ordinal })
        assertEquals(1, document.chapters.count { it.kind == BookChapterKind.EPILOGUE })
    }

    private fun awaitController() {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value && !controller.importing.value }
    }

    private fun show(presentation: ReaderPresentationMode, dark: Boolean) {
        mode.value = presentation
        preferences.value = ReaderPreferences(theme = if (dark) ReaderTheme.DARK else ReaderTheme.LIGHT)
        val initial = ReadingProgress(location = epilogue)
        progress.set(initial)
        compose.runOnUiThread { compose.activity.setContent {
            ListaiTheme(darkTheme = dark) {
                ReaderScreen(document, presentationMode = mode.value, darkTheme = dark,
                    preferences = preferences.value, initialProgress = initial,
                    onProgressChanged = progress::set, onboardingCompleted = true)
            }
        } }
        compose.waitUntil(45_000) { resting() }
    }

    private fun assertOpening() {
        val page = visibleText().single { it.startsWith("Эпилог") }
        assertTrue(page.contains("Дом для света"))
        assertTrue("The opening shares its leaf with the narrative", page.contains("Весной Лида вернулась"))
        assertFalse(page.contains("Глава"))
        assertFalse(page.contains("У каждой карты должны быть свидетели"))
    }

    private fun visitEpilogueThroughContents() {
        compose.onNodeWithTag("reader_top_contents").performClick()
        compose.waitUntil(30_000) { resting() && progress.get().tocEntryIndex != null }
        var turns = 0
        while (epilogue.encoded() !in config()[ReaderContentsTargets]) {
            assertTrue("The epilogue remains part of the permanent table of contents", turns++ < 12)
            val before = visibleText()
            turn(true)
            compose.waitUntil(30_000) { resting() && visibleText() != before }
        }
        val row = config()[ReaderContentsRows][config()[ReaderContentsTargets].indexOf(epilogue.encoded())]
        compose.onNodeWithTag("book_view_demo").performTouchInput { click(row.center) }
        compose.waitUntil(30_000) { resting() && progress.get().tocEntryIndex == null }
        assertOpening()
    }

    private fun turn(forward: Boolean) {
        compose.waitUntil(30_000) { resting() }
        compose.onNodeWithTag("book_view_demo").performTouchInput {
            swipe(Offset(width * if (forward) .92f else .08f, height * .68f),
                Offset(width * if (forward) .08f else .92f, height * .68f), durationMillis = 400)
        }
    }

    private fun config() = compose.onNodeWithTag("book_view_demo").fetchSemanticsNode().config
    private fun visibleText() = config()[SemanticsProperties.Text].map { it.text }
    private fun resting() = compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().singleOrNull()?.config?.let {
        it.getOrElse(ReaderPaginationComplete) { false } && !it.getOrElse(ReaderJumpInProgress) { true } &&
            it.getOrElse(ReaderTurnProgress) { 1f } == 0f
    } == true
    private fun BookLocation.encoded() = "${blockId.value}:$charOffset:$fallbackGlobalOffset"

    private fun capture(name: String) {
        compose.waitForIdle()
        val frame = CountDownLatch(1)
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            if (Build.VERSION.SDK_INT >= 29) view.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
            else view.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    view.post { view.viewTreeObserver.removeOnDrawListener(this) }
                    view.postOnAnimation { view.postOnAnimation { frame.countDown() } }
                }
            })
            view.invalidate()
        }
        assertTrue(frame.await(5, TimeUnit.SECONDS))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(200, 5_000)
        SystemClock.sleep(500)
        val bitmap = requireNotNull(automation.takeScreenshot())
        val output = requireNotNull(compose.activity.getExternalFilesDir("rc7-epilogue")).also { it.mkdirs() }
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(output, "$name.txt").writeText("anchor=${progress.get().location}\n" + visibleText().joinToString("\n---\n"))
        bitmap.recycle()
    }

    private fun epubFixture(): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("OPS/book.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">rc7-epilogue-native-flow</dc:identifier><dc:title>Дом для света</dc:title><dc:language>ru</dc:language></metadata><manifest><item id="body" href="book.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="body"/></spine></package>""")
            entry("OPS/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="book.xhtml#chapter">Глава шестая</a></li><li><a href="book.xhtml#epilogue">Эпилог</a></li></ol></nav></body></html>""")
            entry("OPS/book.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><section id="chapter" epub:type="chapter"><h1>Глава шестая</h1><p>У каждой карты должны быть свидетели.</p></section><section id="epilogue" epub:type="epilogue"><header><h1>Эпилог</h1><h2>Дом для света</h2></header><p>${"Весной Лида вернулась на Тонкий. Море было спокойно. ".repeat(80)}</p></section></body></html>""")
        }
    }.toByteArray()
}

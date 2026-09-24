package com.avstaim.listai

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.feature.reader.ReaderPaginationComplete
import com.avstaim.listai.feature.reader.ReaderSemanticLocation
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V11FormatsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mixedBatchImportsDeduplicatesReadsSharesAndRestoresBothNewFormats() {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        compose.runOnUiThread { controller.completeReaderOnboarding(); controller.returnToShelf() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val imports = File(context.cacheDir, "imports").also { it.mkdirs() }
        val sources = listOf("epub", "pdf").map { extension ->
            File(imports, "v11-sample.$extension").also { target ->
                instrumentation.context.assets.open("v11/sample.$extension").use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        val duplicate = File(imports, "v11-duplicate.epub").also { sources.first().copyTo(it, overwrite = true) }
        val bad = File(imports, "v11-bad.fb2").also { it.writeText("<broken/>") }
        val ids = mutableListOf<BookId>()
        try {
            val uris = (sources + duplicate + bad).map { FileProvider.getUriForFile(context, "com.avstaim.listai.debug.imports", it) }
            compose.runOnUiThread { controller.importBooks(uris) }
            compose.waitUntil(60_000) { controller.importSummary.value != null && !controller.importing.value }
            val summary = requireNotNull(controller.importSummary.value)
            assertEquals(2, summary.added)
            assertEquals(1, summary.duplicates)
            assertEquals(1, summary.failed)
            assertNull(controller.error.value)
            compose.runOnUiThread { controller.dismissImportSummary() }
            // Resolve by original content identity without depending on provider/title language.
            ids += runBlocking { sources.map { source -> controller.repository.import(source.name) { source.inputStream() } } }
            assertEquals(2, ids.distinct().size)
            for ((index, id) in ids.withIndex()) {
                compose.runOnUiThread { controller.open(id) }
                waitForBook(id)
                compose.onNodeWithTag("closed_book_read").performClick()
                val start = location()
                val view = compose.onNodeWithTag("book_view_demo")
                compose.waitUntil(10_000) { view.fetchSemanticsNode().config.contains(SemanticsActions.OnClick) }
                view.performTouchInput { click(Offset(width * .86f, height * .54f)) }
                compose.waitUntil(15_000) { location() != start }
                val anchor = location()
                compose.runOnUiThread { controller.saveOnBackground() }
                compose.activityRule.scenario.recreate()
                waitForBook(id)
                assertEquals(anchor, location())
                val shared = runBlocking { controller.repository.prepareShare(id, File(context.cacheDir, "shared_books")) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.books", shared.file)
                assertEquals(if (index == 0) "application/epub+zip" else "application/pdf", shared.mimeType)
                context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(sources[index].readBytes(), it.readBytes()) }
                compose.runOnUiThread { controller.returnToShelf() }
                compose.waitUntil(15_000) { controller.selection.value == null }
            }
        } finally {
            compose.runOnUiThread { controller.returnToShelf(); ids.forEach(controller::delete) }
            compose.waitUntil(15_000) { runBlocking { controller.repository.books.first().none { it.id in ids } } }
            (sources + duplicate + bad).forEach { it.delete() }
        }
    }

    private fun waitForBook(id: BookId) = compose.waitUntil(30_000) {
        val controller = (compose.activity.application as ListaiApplication).controller
        controller.selection.value?.opened?.book?.id == id &&
            compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().any { it.config.getOrElse(ReaderPaginationComplete) { false } } &&
            compose.onAllNodesWithTag("shelf_reader_transition").fetchSemanticsNodes().any { it.config.getOrElse(ShelfTransitionPhase) { "" } == "READING" }
    }
    private fun location() = compose.onNodeWithTag("book_view_demo").fetchSemanticsNode().config[ReaderSemanticLocation]
}

package com.avstaim.listai

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Saved Bundle addresses survive controller recreation; live controller commands still reset. */
@RunWith(AndroidJUnit4::class)
class ReaderSessionRestorationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun restoredNoteSurvivesNewControllerSerialAndOpeningThenLiveSessionStartsAtRequestedSource() {
        val source = BookBlock.Paragraph(BlockId("source"), listOf(InlineNode.Text("A source call "),
            InlineNode.Link(LinkTarget.Note(NoteId("note")), listOf(InlineNode.Text("1"))),
            InlineNode.Text(" keeps its place. ".repeat(80))))
        val later = BookBlock.Paragraph(BlockId("later"), listOf(InlineNode.Text("A selected bookmark. ".repeat(100))))
        val document = BookDocument(BookId("session-restoration"), BookMetadata("Saved notes"), listOf(source, later),
            notes = listOf(BookNote(NoteId("note"), listOf(BookBlock.Paragraph(BlockId("note-text"),
                listOf(InlineNode.Text("The full note keeps a return path. ".repeat(100))))))))
        var session by mutableLongStateOf(7L)
        var initial by mutableStateOf(ReadingProgress(BookLocation(source.id, 0, 0)))
        var opening by mutableStateOf<Float?>(null)
        var emitReader by mutableStateOf(true)
        var registry by mutableStateOf(SaveableStateRegistry(null) { true })
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    if (emitReader) key(document.id.value) {
                        ListaiTheme {
                            ReaderScreen(document, presentationMode = ReaderPresentationMode.SINGLE,
                                initialProgress = initial, readerSession = session, transitionCoverProgress = opening)
                        }
                    }
                }
            }
        }
        compose.waitUntil(30_000) { node()?.config?.getOrElse(ReaderNoteLinks) { emptyList() }?.isNotEmpty() == true }
        compose.waitForIdle()
        val checkpoint = config()[ReaderSemanticLocation]
        val index = config()[ReaderNoteLinkTargets].indexOfFirst { it.endsWith("|footer") }
        assertTrue(index >= 0)
        val call = config()[ReaderNoteLinks][index]
        book().performTouchInput { click(call.center) }
        awaitNotes()
        val visit = config()[ReaderNoteVisit]
        val noteLocation = config()[ReaderNotesLocation]

        lateinit var saved: Map<String, List<Any?>>
        compose.runOnIdle {
            saved = parcelRoundTrip(registry.performSave())
            emitReader = false
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            // A new controller starts its serial at one and the host reopens the cover.
            registry = SaveableStateRegistry(saved) { true }
            session = 1L
            opening = 0f
            emitReader = true
        }
        awaitNotes()
        assertEquals(visit, config()[ReaderNoteVisit])
        assertEquals(noteLocation, config()[ReaderNotesLocation])
        assertEquals(checkpoint, config()[ReaderSemanticLocation])

        val target = BookLocation(later.id, 5, source.plainText().length + 1L + 5)
        compose.runOnUiThread {
            opening = null
            initial = ReadingProgress(target)
            session = 2L
        }
        val encodedTarget = "${target.blockId.value}:${target.charOffset}:${target.fallbackGlobalOffset}"
        compose.waitUntil(30_000) {
            val state = node()?.config ?: return@waitUntil false
            state[ReaderSemanticLocation] == encodedTarget && state[ReaderNotesLocation].isEmpty() &&
                !state[ReaderJumpInProgress]
        }
        compose.waitForIdle()
        assertEquals("", config()[ReaderNoteVisit])
        assertTrue(config()[ReaderCacheBytes] <= 64L * 1024 * 1024)
    }

    private fun awaitNotes() {
        compose.waitUntil(30_000) {
            val state = node()?.config ?: return@waitUntil false
            state[ReaderNotesLocation].isNotEmpty() && !state[ReaderJumpInProgress]
        }
        compose.waitForIdle()
    }

    private fun book() = compose.onNodeWithTag("book_view_demo")
    private fun node() = compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().singleOrNull()
    private fun config() = book().fetchSemanticsNode().config

    /** Exercise the actual Android Bundle encoding, not an in-memory copy of the visit. */
    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun parcelRoundTrip(values: Map<String, List<Any?>>): Map<String, List<Any?>> {
        val bundle = Bundle().apply {
            classLoader = this@ReaderSessionRestorationTest.javaClass.classLoader
            values.forEach { (key, value) -> putParcelableArrayList(key, ArrayList(value) as ArrayList<Parcelable?>) }
        }
        val outgoing = Parcel.obtain()
        val bytes = try { outgoing.writeBundle(bundle); outgoing.marshall() } finally { outgoing.recycle() }
        val incoming = Parcel.obtain()
        val restored = try {
            incoming.unmarshall(bytes, 0, bytes.size)
            incoming.setDataPosition(0)
            requireNotNull(incoming.readBundle(javaClass.classLoader))
        } finally { incoming.recycle() }
        return restored.keySet().associateWith { requireNotNull(restored.getParcelableArrayList<Parcelable?>(it)) }
    }
}

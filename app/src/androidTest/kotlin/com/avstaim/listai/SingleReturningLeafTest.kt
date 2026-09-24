package com.avstaim.listai

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.*
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses real paginated text, raster surfaces, pointer input, and settle checkpoints. */
@RunWith(AndroidJUnit4::class)
class SingleReturningLeafTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun previousLeafReturnsWithItsOwnInkAndCommitsOnlyAfterSettlingInBothThemes() {
        val controller = (compose.activity.application as ListaiApplication).controller
        compose.waitUntil(30_000) { !controller.busy.value }
        for (dark in listOf(false, true)) {
            val document = document(dark)
            val commits = CopyOnWriteArrayList<ReadingProgress>()
            compose.runOnUiThread {
                compose.activity.setContent {
                    ListaiTheme(darkTheme = dark) {
                        ReaderScreen(document, presentationMode = ReaderPresentationMode.SINGLE,
                            darkTheme = dark, preferences = ReaderPreferences(theme = if (dark) ReaderTheme.DARK else ReaderTheme.LIGHT),
                            initialProgress = ReadingProgress(BookLocation(BlockId("paragraph-4"), 0, 0), CoverPosition.OPEN),
                            onProgressChanged = { commits += it }, onboardingCompleted = true)
                    }
                }
            }
            compose.waitUntil(30_000) {
                node()?.config?.getOrElse(ReaderPaginationComplete) { false } == true && commits.isNotEmpty()
            }
            compose.waitForIdle()
            // Normalize an arbitrary restored source offset to a settled page boundary first.
            val restored = location()
            tapTurn(forward = true)
            compose.waitUntil(20_000) { location() != restored && config()[ReaderTurnProgress] == 0f }
            val original = location()
            compose.mainClock.autoAdvance = false
            var cancellationPointerDown = false
            try {
                tapTurn(forward = true)
                var returningInk = ""
                for (phase in listOf(.08f, .45f, .80f)) {
                    awaitPhase("forward", phase)
                    assertEquals("Current anchor must stay put while its leaf departs", original, location())
                    assertEquals("page", config()[ReaderTurnSubject])
                    val ink = config()[ReaderTurnSourceText]
                    assertTrue("The moving leaf needs text from its first frame", ink.isNotBlank())
                    if (returningInk.isEmpty()) returningInk = ink else assertEquals(returningInk, ink)
                }
                awaitRest { location() != original }
                val following = location()
                val followingCheckpoint = commits.last()
                // Let the newly committed page install its pointer-input node and prepared
                // surface window before holding a partial gesture across test-clock frames.
                compose.mainClock.autoAdvance = true
                compose.waitForIdle()
                compose.mainClock.autoAdvance = false

                // A short, slow rightward drag begins returning the previous leaf, then cancels.
                book().performTouchInput {
                    down(Offset(width * .28f, height * .48f))
                    moveTo(Offset(width * .31f, height * .48f), 350)
                    moveTo(Offset(width * .38f, height * .48f), 400)
                }
                cancellationPointerDown = true
                advanceUntil("Backward cancellation drag (dark=$dark)") {
                    config()[ReaderTurnDirection] == "backward" && config()[ReaderTurnProgress] in .01f.. .45f
                }
                assertEquals(following, location())
                assertEquals("A backward turn returns the prior leaf; it must not lift the current one",
                    returningInk, config()[ReaderTurnSourceText])
                assertEquals(followingCheckpoint, commits.last())
                book().performTouchInput { advanceEventTime(600); up() }
                cancellationPointerDown = false
                awaitRest { location() == following }
                assertEquals("Cancelling cannot write a destination checkpoint", followingCheckpoint, commits.last())

                tapTurn(forward = false)
                for (phase in listOf(.08f, .45f, .80f)) {
                    awaitPhase("backward", phase)
                    assertEquals("The current page remains underneath until the leaf settles", following, location())
                    assertEquals("page", config()[ReaderTurnSubject])
                    assertEquals(returningInk, config()[ReaderTurnSourceText])
                    assertEquals(followingCheckpoint, commits.last())
                }
                awaitRest { location() == original }
                assertTrue(config()[ReaderCacheBytes] <= 64L * 1024 * 1024)
            } finally {
                if (cancellationPointerDown) runCatching { book().performTouchInput { cancel() } }
                compose.mainClock.autoAdvance = true
            }
            compose.waitForIdle()
        }
    }

    private fun tapTurn(forward: Boolean) {
        book().performTouchInput { click(Offset(width * if (forward) .86f else .14f, height * .52f)) }
    }

    private fun awaitPhase(direction: String, minimum: Float) = advanceUntil("$direction at progress $minimum") {
        val state = node()?.config ?: return@advanceUntil false
        state[ReaderTurnDirection] == direction && state[ReaderTurnProgress] >= minimum && state[ReaderTurnProgress] < .99f
    }

    private fun awaitRest(predicate: () -> Boolean) = advanceUntil {
        config()[ReaderTurnProgress] == 0f && predicate()
    }

    private fun advanceUntil(description: String = "Resting page", predicate: () -> Boolean) {
        val observed = ArrayDeque<String>()
        try {
            compose.waitUntil(20_000) {
                compose.mainClock.advanceTimeByFrame()
                val state = node()?.config
                val observation = if (state == null) "no book node" else
                    "direction=${state.getOrElse(ReaderTurnDirection) { "" }}, " +
                        "progress=${state.getOrElse(ReaderTurnProgress) { -1f }}, " +
                        "subject=${state.getOrElse(ReaderTurnSubject) { "" }}, " +
                        "location=${state.getOrElse(ReaderSemanticLocation) { "" }}, " +
                        "jump=${state.getOrElse(ReaderJumpInProgress) { false }}"
                if (observed.lastOrNull() != observation) {
                    observed.addLast(observation)
                    if (observed.size > 16) observed.removeFirst()
                }
                predicate()
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("$description timed out. Observed states:\n${observed.joinToString("\n")}", timeout)
        }
    }

    private fun book() = compose.onNodeWithTag("book_view_demo")
    private fun node() = compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().singleOrNull()
    private fun config() = requireNotNull(node()).config
    private fun location() = config()[ReaderSemanticLocation]

    private fun document(dark: Boolean) = BookDocument(BookId("single-return-$dark"),
        BookMetadata("The Returning Leaf", language = "en"), List(24) { index ->
            BookBlock.Paragraph(BlockId("paragraph-$index"), listOf(InlineNode.Text("Passage ${index + 1}. " +
                "The words stay printed on this particular leaf as it rises from the spine and returns to the reader. ".repeat(8))))
        })
}

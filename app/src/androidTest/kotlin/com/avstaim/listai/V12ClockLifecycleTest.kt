package com.avstaim.listai

import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.model.*
import com.avstaim.listai.feature.reader.*
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses real system time broadcasts and the production reader, without a clock test seam. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 36)
class V12ClockLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun liveClockTracksSystemSettingsAndUnregistersWhenHiddenOrStopped() {
        val originalFormat = shell("settings get system time_12_24").trim()
        val originalAutoZone = shell("settings get global auto_time_zone").trim()
        val originalZone = shell("getprop persist.sys.timezone").trim()
        require(originalFormat in setOf("null", "12", "24"))
        require(originalAutoZone in setOf("null", "0", "1"))
        require(originalZone.matches(Regex("[A-Za-z0-9_+/-]+")))
        val preferences = mutableStateOf(ReaderPreferences(showClock = true))
        val active = mutableStateOf(true)
        val initial = ReadingProgress(location = BookLocation(BlockId("clock-text"), 24, 24))
        val progress = AtomicReference(initial)
        var stopped = false
        try {
            checkedShell("settings put global auto_time_zone 0")
            checkedShell("cmd alarm set-timezone Etc/UTC")
            checkedShell("settings put system time_12_24 12")
            val controller = (compose.activity.application as ListaiApplication).controller
            compose.waitUntil(30_000) { !controller.busy.value }
            val document = fixture()
            compose.runOnUiThread {
                compose.activity.setContent {
                    ListaiTheme {
                        ReaderScreen(document, initialProgress = initial,
                            preferences = preferences.value, active = active.value,
                            onProgressChanged = progress::set,
                            presentationMode = ReaderPresentationMode.SINGLE,
                            onboardingCompleted = true)
                    }
                }
            }
            awaitRest()
            awaitClock("Etc/UTC")
            assertFalse(DateFormat.is24HourFormat(compose.activity))
            awaitReceiverCount(1)
            val before = readerSnapshot(progress.get())
            assertTrue("The fixture must have rasterized actual book pages", before.renders > 0)

            // A direct settings write need not broadcast TIME_SET. The next real minute tick
            // must refresh both the number and the selected system format, without repagination.
            val minuteBefore = System.currentTimeMillis() / 60_000L
            checkedShell("settings put system time_12_24 24")
            compose.waitUntil(70_000) {
                System.currentTimeMillis() / 60_000L != minuteBefore &&
                    DateFormat.is24HourFormat(compose.activity) && clockLabel() == expectedTime("Etc/UTC")
            }
            assertReaderUnchanged("natural minute tick and 12/24 change", before, progress.get())

            val utcLabel = clockLabel()
            checkedShell("cmd alarm set-timezone Pacific/Honolulu")
            awaitClock("Pacific/Honolulu")
            assertNotEquals("The timezone broadcast must change the displayed hour", utcLabel, clockLabel())
            assertReaderUnchanged("timezone broadcast", before, progress.get())
            awaitReceiverCount(1)

            compose.runOnUiThread { preferences.value = preferences.value.copy(showClock = false) }
            compose.onNodeWithTag("reader_clock", useUnmergedTree = true).assertDoesNotExist()
            awaitReceiverCount(0)
            assertReaderUnchanged("clock disabled", before, progress.get())
            checkedShell("cmd alarm set-timezone Asia/Tokyo")
            compose.runOnUiThread { preferences.value = preferences.value.copy(showClock = true) }
            awaitClock("Asia/Tokyo")
            awaitReceiverCount(1)
            assertReaderUnchanged("clock enabled with fresh time", before, progress.get())

            compose.runOnUiThread { active.value = false }
            awaitReceiverCount(0)
            val inactiveLabel = clockLabel()
            checkedShell("cmd alarm set-timezone Etc/UTC")
            compose.waitForIdle()
            assertEquals("An inactive reader must not receive timezone updates", inactiveLabel, clockLabel())
            compose.runOnUiThread { active.value = true }
            awaitClock("Etc/UTC")
            awaitReceiverCount(1)
            assertReaderUnchanged("reader reactivated", before, progress.get())

            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            stopped = true
            awaitReceiverCount(0)
            checkedShell("cmd alarm set-timezone Pacific/Honolulu")
            assertEquals("No clock receiver may reappear while the activity is stopped", 0, clockReceivers().size)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            stopped = false
            awaitRest()
            awaitClock("Pacific/Honolulu")
            awaitReceiverCount(1)
            assertReaderUnchanged("activity resumed with fresh time", before, progress.get())
        } finally {
            // Restore every device setting even when an assertion or lifecycle transition fails.
            try {
                if (stopped) compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                compose.runOnUiThread { preferences.value = preferences.value.copy(showClock = false) }
            } finally {
                try {
                    restoreSetting("system", "time_12_24", originalFormat)
                } finally {
                    try {
                        checkedShell("cmd alarm set-timezone $originalZone")
                    } finally {
                        restoreSetting("global", "auto_time_zone", originalAutoZone)
                    }
                }
            }
        }
    }

    private fun awaitRest() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("book_view_demo").fetchSemanticsNodes().singleOrNull()?.config?.let {
                it.getOrElse(ReaderPaginationComplete) { false } &&
                    !it.getOrElse(ReaderJumpInProgress) { true } &&
                    it.getOrElse(ReaderTurnProgress) { 1f } == 0f
            } == true
        }
        // Pagination completion precedes publication of the last prepared page surface.
        var lastRenders = -1L
        var stableSince = SystemClock.elapsedRealtime()
        compose.waitUntil(10_000) {
            val renders = config()[ReaderSurfaceRenders]
            if (renders != lastRenders) {
                lastRenders = renders
                stableSince = SystemClock.elapsedRealtime()
            }
            SystemClock.elapsedRealtime() - stableSince >= 500L
        }
    }

    private fun awaitClock(zone: String) = compose.waitUntil(10_000) {
        clockLabel() == expectedTime(zone)
    }

    private fun clockLabel(): String? = compose.onAllNodesWithTag("reader_clock", useUnmergedTree = true)
        .fetchSemanticsNodes().singleOrNull()?.config?.getOrElse(SemanticsProperties.Text) { emptyList() }
        ?.joinToString("") { it.text }

    private fun expectedTime(zone: String): String = DateFormat.getTimeFormat(compose.activity).apply {
        timeZone = TimeZone.getTimeZone(zone)
    }.format(Date())

    private data class ReaderSnapshot(val layout: String, val anchor: String,
        val progress: ReadingProgress, val renders: Long)

    private fun readerSnapshot(progress: ReadingProgress): ReaderSnapshot = config().let {
        ReaderSnapshot(it[ReaderLayoutIdentity], it[ReaderSemanticLocation], progress, it[ReaderSurfaceRenders])
    }

    private fun assertReaderUnchanged(event: String, before: ReaderSnapshot, progress: ReadingProgress) {
        val after = readerSnapshot(progress)
        assertEquals("$event must preserve the pagination session", before.layout, after.layout)
        assertEquals("$event must preserve the semantic anchor", before.anchor, after.anchor)
        assertEquals("$event must preserve the durable reading position", before.progress, after.progress)
        assertEquals("$event must not rasterize book pages", before.renders, after.renders)
        println("V12ClockEvidence: $event; label=${clockLabel()}; renders=${after.renders}; anchor=${after.anchor}")
    }

    private fun config() = compose.onNodeWithTag("book_view_demo").fetchSemanticsNode().config

    private fun awaitReceiverCount(expected: Int) {
        var actual = emptyList<String>()
        try {
            compose.waitUntil(10_000) {
                actual = clockReceivers()
                actual.size == expected
            }
        } catch (failure: AssertionError) {
            throw AssertionError("Expected $expected live ReaderClock receivers for pid=${Process.myPid()}, " +
                "found ${actual.size}: ${actual.joinToString("\n")}", failure)
        }
    }

    private fun clockReceivers(): List<String> {
        val dump = shell("dumpsys activity broadcasts")
        assertTrue("Broadcast diagnostics must expose registered receivers", dump.contains("Registered Receivers:"))
        val registered = dump.substringAfter("Registered Receivers:").substringBefore("Receiver Resolver Table:")
        return Regex("(?m)^\\s*\\* ReceiverList\\{").split(registered).drop(1).filter { block ->
            Regex("\\bpid=${Process.myPid()}\\b").containsMatchIn(block) &&
                listOf("TIME_TICK", "TIME_SET", "TIMEZONE_CHANGED", "LOCALE_CHANGED").all { action ->
                    block.contains("Action: \"android.intent.action.$action\"")
                }
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun checkedShell(command: String) {
        val output = shell(command)
        assertFalse("Command failed: $command\n$output",
            output.contains("Exception") || output.contains("Error:") || output.contains("Unknown command"))
    }

    private fun restoreSetting(namespace: String, key: String, value: String) {
        checkedShell(if (value == "null") "settings delete $namespace $key" else "settings put $namespace $key $value")
    }

    private fun fixture() = BookDocument(BookId("v12-clock-lifecycle"),
        BookMetadata("The quiet clock", listOf("Reader One"), "en"),
        listOf(BookBlock.Paragraph(BlockId("clock-text"), listOf(InlineNode.Text(
            "A quiet page keeps its place while the clock changes. ".repeat(12))))))
}

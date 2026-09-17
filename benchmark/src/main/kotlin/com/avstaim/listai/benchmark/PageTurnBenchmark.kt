package com.avstaim.listai.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class PageTurnBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @After fun restoreOrientation() = UiDevice.getInstance(
        InstrumentationRegistry.getInstrumentation()).unfreezeRotation()

    @Test fun repeatedSinglePageTurns() = repeatedPageTurns(spread = false)

    @Test fun repeatedSpreadPageTurns() = repeatedPageTurns(spread = true)

    private fun repeatedPageTurns(spread: Boolean) = benchmarkRule.measureRepeated(
        packageName = ReaderBenchmarkFixture.PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        // Repeatable AOT rendering without relying on an absent bundled baseline profile.
        compilationMode = CompilationMode.Full(),
        iterations = 5,
        setupBlock = { ReaderBenchmarkFixture.importBook(this, spread) },
    ) {
        val source = ReaderBenchmarkFixture.awaitPage(device)
        repeat(6) { ReaderBenchmarkFixture.turn(device, forward = true, spread = spread) }
        repeat(6) { ReaderBenchmarkFixture.turn(device, forward = false, spread = spread) }
        assertEquals("The measured walk must return to the same leaf", source.number,
            ReaderBenchmarkFixture.awaitPage(device).number)
    }
}

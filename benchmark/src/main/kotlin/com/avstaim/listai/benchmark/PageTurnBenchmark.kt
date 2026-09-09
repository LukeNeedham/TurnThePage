package com.avstaim.listai.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class PageTurnBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun repeatedPageTurns() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()
        },
    ) {
        val displayWidth = device.displayWidth
        val displayHeight = device.displayHeight
        val outerRight = (displayWidth * 0.90f).toInt()
        val outerLeft = (displayWidth * 0.10f).toInt()
        val innerRight = (displayWidth * 0.72f).toInt()
        val innerLeft = (displayWidth * 0.28f).toInt()
        val grabY = (displayHeight * 0.70f).toInt()
        val releaseY = (displayHeight * 0.62f).toInt()

        repeat(TURNS_PER_DIRECTION) {
            device.swipe(
                outerRight,
                grabY,
                innerLeft,
                releaseY,
                SWIPE_STEPS,
            )
            device.waitForIdle()
        }

        repeat(TURNS_PER_DIRECTION) {
            device.swipe(
                outerLeft,
                grabY,
                innerRight,
                releaseY,
                SWIPE_STEPS,
            )
            device.waitForIdle()
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.avstaim.listai"
        const val TURNS_PER_DIRECTION = 6
        const val SWIPE_STEPS = 40
    }
}

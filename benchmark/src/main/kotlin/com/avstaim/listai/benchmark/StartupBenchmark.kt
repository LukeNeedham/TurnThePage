package com.avstaim.listai.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @After fun restoreOrientation() = androidx.test.uiautomator.UiDevice.getInstance(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()).unfreezeRotation()

    @Test
    fun coldStartupIntoImportedBook() = benchmarkRule.measureRepeated(
        packageName = ReaderBenchmarkFixture.PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = {
            ReaderBenchmarkFixture.importBook(this, spread = false)
            pressHome()
        },
    ) {
        startActivityAndWait()
        ReaderBenchmarkFixture.awaitPage(device)
    }
}

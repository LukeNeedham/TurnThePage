package com.avstaim.listai.benchmark

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.core.content.FileProvider
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.UiDevice
import java.io.File

/** Uses the shipping importer and accessible reader, without a special app entry point. */
internal object ReaderBenchmarkFixture {
    const val PACKAGE_NAME = "com.avstaim.listai"
    private const val MARKER = "Stage nine benchmark paragraph"
    private const val TIMEOUT_MS = 45_000L
    private var activeMarker = MARKER
    private var expectedSpread = false
    private var observedBook = "No matching accessible book"
    private var observedRoot = "No active window"
    private val observedNodes = mutableListOf<String>()
    private val descriptions = setOf("Interactive physical book", "Интерактивная физическая книга")
    private val pageNumber = Regex("^(\\d[\\d\\s,\u00a0]*) / (\\d[\\d\\s,\u00a0]*)$")

    data class Page(val number: Int, val total: Int, val text: String, val bounds: Rect, val visiblePages: Int)

    fun importBook(scope: MacrobenchmarkScope, spread: Boolean) {
        expectedSpread = spread
        // This suite targets a portrait-natural phone emulator. Absolute rotations
        // avoid choosing a rotation from stale display dimensions after another test.
        if (spread) scope.device.setOrientationLeft() else scope.device.setOrientationNatural()
        val context = InstrumentationRegistry.getInstrumentation().context
        check(context.packageName == "$PACKAGE_NAME.benchmark") {
            "The fixture provider must belong to the self-instrumenting benchmark APK"
        }
        // Separate stable imports avoid carrying a single-page checkpoint into a spread.
        val mode = if (spread) "spread" else "single"
        activeMarker = "$MARKER $mode"
        val directory = File(context.filesDir, "fixtures").also { check(it.mkdirs() || it.isDirectory) }
        val fixture = File(directory, "stage9-$mode.fb2")
        fixture.writeText(buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink"><description><title-info><book-title>Stage 9 benchmark $mode</book-title><lang>en</lang></title-info></description><body><section id="story">""")
            repeat(120) { index ->
                append("<p id=\"source-$index\">$activeMarker $index. ")
                if (index % 8 == 0) append("A printed call<a l:href=\"#note-$index\" type=\"note\">[${index / 8 + 1}]</a>. ")
                append("$activeMarker $index. A quiet paper leaf keeps its source text and its own place during a turn. ".repeat(18))
                append("</p>")
            }
            append("</section></body><body name=\"notes\">")
            for (index in 0 until 120 step 8) {
                append("<section id=\"note-$index\"><title><p>${index / 8 + 1}</p></title><p>")
                append("The printed explanation retains a direct return to the source. ".repeat(12))
                append("</p></section>")
            }
            append("</body></FictionBook>")
        })
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fixtures", fixture)
        context.grantUriPermission(PACKAGE_NAME, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        scope.startActivityAndWait(Intent(Intent.ACTION_VIEW).apply {
            setClassName(PACKAGE_NAME, "$PACKAGE_NAME.MainActivity")
            setDataAndType(uri, "application/x-fictionbook+xml")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        scope.device.waitForIdle()
        val page = awaitPage(scope.device, stable = true)
        check(page.total > 20) { "The fixture needs enough pages for a real reading walk: $page" }
        // Completed source totals, published ink, and an enabled turn prove readiness.
        // Re-imports are deduplicated; no application data is cleared.
        check(page.text.contains(activeMarker)) { "The generated book did not reach the reader" }
        scope.device.waitForIdle()
    }

    fun awaitPage(device: UiDevice, expectedNumber: Int? = null, stable: Boolean = false): Page {
        var previous: Page? = null
        val page = device.wait(object : Condition<UiDevice, Page> {
            override fun apply(device: UiDevice): Page? {
                val current = currentPage()?.takeIf { expectedNumber == null || it.number == expectedNumber }
                val accepted = current?.takeIf { !stable || it == previous }
                previous = current
                return accepted
            }
        }, TIMEOUT_MS)
        if (page == null) {
            val context = InstrumentationRegistry.getInstrumentation().context
            val diagnostic = File(context.getExternalFilesDir(null), "reader-readiness-failure.xml")
            runCatching { device.dumpWindowHierarchy(diagnostic) }
            error("Reader did not expose a completed source page (expected=$expectedNumber): " +
                "expectedMode=${if (expectedSpread) "spread" else "single"}; " +
                "$observedRoot; $observedBook; nodes=$observedNodes; hierarchy=${diagnostic.absolutePath}")
        }
        return page
    }

    fun turn(device: UiDevice, forward: Boolean, spread: Boolean): Page {
        check(spread == expectedSpread) { "Measured presentation must match fixture setup" }
        val before = awaitPage(device)
        val distance = if (spread) 2 else 1
        val expected = before.number + if (forward) distance else -distance
        check(expected in 1..before.total) { "Benchmark reached a physical book boundary: $before" }
        swipe(device, before.bounds, forward)
        return awaitPage(device, expected)
    }

    private fun swipe(device: UiDevice, bounds: Rect, forward: Boolean) {
        val from = if (forward) .84f else .16f
        val to = if (forward) .16f else .84f
        check(device.swipe((bounds.left + bounds.width() * from).toInt(),
            (bounds.top + bounds.height() * .58f).toInt(),
            (bounds.left + bounds.width() * to).toInt(),
            (bounds.top + bounds.height() * .55f).toInt(), 24))
    }

    private fun currentPage(): Page? {
        observedBook = "No matching accessible book"
        observedNodes.clear()
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        observedRoot = "rootPackage=${root?.packageName}, rootClass=${root?.className}"
        if (root == null) return null
        return findPage(root)
    }

    private fun findPage(node: AccessibilityNodeInfo): Page? {
        try {
            if (observedNodes.size < 8 && (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank())) {
                observedNodes += "${node.packageName}: text='${node.text?.take(100)}', " +
                    "description='${node.contentDescription?.take(80)}'"
            }
            if (node.packageName?.toString() == PACKAGE_NAME && node.contentDescription?.toString() in descriptions) {
                val state = AccessibilityNodeInfoCompat.wrap(node).stateDescription?.toString().orEmpty()
                val match = pageNumber.matchEntire(state)
                val ink = node.text?.toString().orEmpty()
                val bounds = Rect().also(node::getBoundsInScreen)
                // PhysicalBookView joins published leaf texts with a blank line. This
                // generated corpus has no blank lines within a source/footnote leaf,
                // so the count proves the actual presentation, not just orientation.
                val visiblePages = ink.split("\n\n").count { it.contains(activeMarker) }
                val correctMode = visiblePages == (if (expectedSpread) 2 else 1)
                val correctOrientation = if (expectedSpread) bounds.width() > bounds.height()
                    else bounds.height() > bounds.width()
                observedBook = "state='$state', clickable=${node.isClickable}, " +
                    "bounds=$bounds, sourceLeaves=$visiblePages, marker=${ink.contains(activeMarker)}, " +
                    "ink='${ink.take(160)}'"
                if (match != null && ink.contains(activeMarker) && node.isClickable && correctMode && correctOrientation) {
                    fun number(value: String) = value.filter(Char::isDigit).toInt()
                    return Page(number(match.groupValues[1]), number(match.groupValues[2]), ink, bounds, visiblePages)
                }
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                findPage(child)?.let { return it }
            }
            return null
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }
}

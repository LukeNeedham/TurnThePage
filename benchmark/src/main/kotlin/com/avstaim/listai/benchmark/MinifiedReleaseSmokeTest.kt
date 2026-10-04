package com.avstaim.listai.benchmark

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs outside the app process: obfuscated classes are exercised only through shipping entry points. */
@RunWith(AndroidJUnit4::class)
class MinifiedReleaseSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private val device = UiDevice.getInstance(instrumentation)
    private val output = File(context.getExternalFilesDir(null), "release-smoke").also { it.mkdirs() }
    private val fixtures = File(context.filesDir, "fixtures").also { it.mkdirs() }
    private val packageName = ReaderBenchmarkFixture.PACKAGE_NAME
    private val pagePattern = Regex("^(\\d[\\d\\s,\u00a0]*) / (\\d[\\d\\s,\u00a0]*)$")

    @After fun finish() {
        device.dumpWindowHierarchy(File(output, "final.xml"))
        device.takeScreenshot(File(output, "final.png"))
        device.unfreezeRotation()
    }

    @Test fun importsTurnsAndRestoresInOptimizedApp() {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        assertFalse("Run against the non-debuggable benchmark app", info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        device.setOrientationNatural()
        val fb2 = File(fixtures, "release-smoke.fb2").apply {
            writeText(buildString {
                append("""<?xml version="1.0"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><book-title>Release smoke FB2</book-title><lang>en</lang></title-info></description><body><section>""")
                repeat(60) { index ->
                    append("<p>Release smoke paragraph $index. ")
                    append("A quiet paper page keeps the reader's place across a cold launch. ".repeat(12))
                    append("</p>")
                }
                append("</section></body></FictionBook>")
            })
        }
        verifyBook(fb2, "application/x-fictionbook+xml", "Release smoke")
        verifyBook(copyFixture("epub"), "application/epub+zip", null)

        // API 26/27 PDFium must not retain a failed parser's error in the next import.
        val broken = File(fixtures, "release-smoke-broken.pdf").apply { writeText("%PDF-1.4\nnot a document") }
        import(broken, "application/pdf")
        val error = Pattern.compile("Could not complete the operation|Не удалось.*")
        assertTrue("Malformed PDF must produce a recoverable error", device.wait(Until.hasObject(By.text(error)), 30_000))
        device.findObject(By.text(Pattern.compile("OK|ОК")))?.click() ?: device.pressBack()
        verifyBook(copyFixture("pdf"), "application/pdf", null)
        Log.i("ReleaseSmoke", "PASS: FB2, EPUB, malformed PDF recovery, PDF, turns and cold restart")
    }

    private fun copyFixture(extension: String) = File(fixtures, "release-smoke.$extension").also { target ->
        context.assets.open("v11/sample.$extension").use { input -> target.outputStream().use(input::copyTo) }
    }

    private fun import(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fixtures", file)
        context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setClassName(packageName, "$packageName.MainActivity")
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun verifyBook(file: File, mimeType: String, marker: String?) {
        import(file, mimeType)
        val first = awaitPage(openCover = true)
        assertTrue("Fixture needs at least two source pages: $first", first.total >= 2)
        if (marker != null) assertTrue("Imported source text must be visible", first.text.contains(marker))
        // From an existing persisted import, make room for a forward turn first.
        val start = if (first.number == first.total) turn(first, forward = false) else first
        val next = turn(start, forward = true)
        assertEquals(start.number + 1, next.number)
        assertEquals(start.number, turn(next, forward = false).number)
        val saved = turn(awaitPage(), forward = true)
        device.takeScreenshot(File(output, "${file.extension}-reading.png"))
        device.pressHome()
        device.waitForIdle()
        device.executeShellCommand("am force-stop $packageName")
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(packageName)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        val restored = awaitPage(openCover = true)
        assertEquals("Room reading checkpoint after process death", saved.number, restored.number)
        assertEquals("Restored source page", saved.text, restored.text)
        Log.i("ReleaseSmoke", "PASS: ${file.extension} pages=${saved.total}, restored=${saved.number}")
    }

    private fun turn(page: Page, forward: Boolean): Page {
        val from = if (forward) .84f else .16f
        val to = 1f - from
        val bounds = page.bounds
        assertTrue(device.swipe((bounds.left + bounds.width() * from).toInt(),
            (bounds.top + bounds.height() * .58f).toInt(),
            (bounds.left + bounds.width() * to).toInt(),
            (bounds.top + bounds.height() * .55f).toInt(), 24))
        return awaitPage(expected = page.number + if (forward) 1 else -1)
    }

    private data class Page(val number: Int, val total: Int, val text: String, val bounds: Rect)

    private fun awaitPage(expected: Int? = null, openCover: Boolean = false): Page {
        var previous: Page? = null
        return requireNotNull(device.wait(object : Condition<UiDevice, Page> {
            override fun apply(device: UiDevice): Page? {
                if (openCover) {
                    device.findObject(By.text(Pattern.compile("(?i)Got it|Понятно")))?.click()
                    device.findObject(By.text(Pattern.compile("Skip|Пропустить")))?.click()
                    device.findObject(By.text(Pattern.compile("Read|Читать")))?.click()
                }
                val current = instrumentation.uiAutomation.rootInActiveWindow?.let(::findPage)
                    ?.takeIf { expected == null || it.number == expected }
                val stable = current?.takeIf { it == previous }
                previous = current
                return stable
            }
        }, 45_000)) { "Reader did not publish stable page $expected; see release-smoke/final.xml" }
    }

    private fun findPage(node: AccessibilityNodeInfo): Page? {
        try {
            if (node.packageName?.toString() == packageName && node.contentDescription?.toString() in
                setOf("Interactive physical book", "Интерактивная физическая книга")) {
                val state = AccessibilityNodeInfoCompat.wrap(node).stateDescription?.toString().orEmpty()
                val match = pagePattern.matchEntire(state)
                if (match != null && node.isClickable) {
                    fun number(value: String) = value.filter(Char::isDigit).toInt()
                    return Page(number(match.groupValues[1]), number(match.groupValues[2]),
                        node.text?.toString().orEmpty(), Rect().also(node::getBoundsInScreen))
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::findPage)?.let { return it }
            }
            return null
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }
}

package com.avstaim.listai

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.designsystem.ListaiTheme
import com.avstaim.listai.core.designsystem.rememberBookCoverSurface
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Printed metadata must remain legible now that shelf captions are removed. */
@RunWith(AndroidJUnit4::class)
class Rc5CoverTypographyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun compoundTitleWrapsAtItsHyphenWithoutAnOrphanedLetter() {
        val layouts = CopyOnWriteArrayList<TextLayoutResult>()
        compose.runOnUiThread {
            compose.activity.setContent {
                ListaiTheme {
                    for (scale in listOf(1f, 1.5f)) {
                        CompositionLocalProvider(LocalDensity provides Density(2f, scale)) {
                            for (width in listOf(176, 304, 560)) {
                                val surface = rememberBookCoverSurface("Человек-амфибия", "Александр Беляев",
                                    IntSize(width, width * 3 / 2))
                                SideEffect { layouts += requireNotNull(surface.title) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { layouts.size >= 6 }
        for (layout in layouts) {
            val text = layout.layoutInput.text.text
            val lines = (0 until layout.lineCount).map { index ->
                text.substring(layout.getLineStart(index), layout.getLineEnd(index, visibleEnd = true))
                    .replace("\u200B", "").trim()
            }.filter(String::isNotEmpty)
            assertFalse("The printed title must fit its cover", layout.hasVisualOverflow)
            assertTrue("A title must break at its hyphen, not inside a word: $lines",
                lines == listOf("Человек-амфибия") || lines == listOf("Человек-", "амфибия"))
        }
    }
}

package com.avstaim.listai.feature.reader.bookview

import android.graphics.Paint
import android.graphics.Typeface
import android.text.style.StyleSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avstaim.listai.core.designsystem.PLYOS_FONT_FAMILY_ID
import com.avstaim.listai.core.designsystem.PlyosTypefaceFamily
import com.avstaim.listai.core.model.InlineNode
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.PageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlyosTypographyTest {
    @Test
    fun bundledFamilyFeedsNativeMeasurementAndAllFourStyles() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val family = PlyosTypefaceFamily.load(resources)
        val key = LayoutKey(420, 720, PageMode.SINGLE, localeTag = "ru")

        assertEquals(PLYOS_FONT_FAMILY_ID, key.fontFamilyId)
        listOf(Typeface.NORMAL, Typeface.BOLD, Typeface.ITALIC, Typeface.BOLD_ITALIC).forEach { style ->
            val typeface = family.resolve(style)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                textSize = 42f
            }
            assertEquals(style, typeface.style)
            listOf("П", "л", "ё", "с", "L", "i").forEach { glyph ->
                assertTrue("Missing $glyph in style $style", paint.hasGlyph(glyph))
            }
            assertSame(typeface, textPaint(key, style = style, typefaces = family).typeface)
        }

        val richText = listOf(InlineNode.Strong(listOf(
            InlineNode.Emphasis(listOf(InlineNode.Text("жирный курсив"))),
        ))).toSpannedText(typefaces = family)
        val inlinePaint = textPaint(key, typefaces = family)
        richText.getSpans(0, richText.length, StyleSpan::class.java)
            .forEach { it.updateMeasureState(inlinePaint) }
        assertSame(family.resolve(Typeface.BOLD_ITALIC), inlinePaint.typeface)
    }
}

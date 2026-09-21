package com.avstaim.listai.core.designsystem

import android.content.res.Resources
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/** Stable pagination/cache identity for the bundled Plyos 0.100 metrics. */
const val PLYOS_FONT_FAMILY_ID = "plyos-0.100"

/** The one book family used by Compose-rendered paper and fallback covers. */
val PlyosFontFamily = FontFamily(
    Font(R.font.plyos_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.plyos_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.plyos_bold, FontWeight.Bold, FontStyle.Normal),
    Font(R.font.plyos_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

/**
 * Android's native text stack counterpart to [PlyosFontFamily].
 *
 * Pagination measurement and bitmap rasterization must resolve styles through the same family;
 * otherwise a bold or italic span can change line breaks between the measured and printed leaf.
 */
@Immutable
class PlyosTypefaceFamily private constructor(
    private val normal: Typeface,
    private val bold: Typeface,
    private val italic: Typeface,
    private val boldItalic: Typeface,
) {
    fun resolve(style: Int): Typeface = when (style and Typeface.BOLD_ITALIC) {
        Typeface.BOLD -> bold
        Typeface.ITALIC -> italic
        Typeface.BOLD_ITALIC -> boldItalic
        else -> normal
    }

    companion object {
        fun load(resources: Resources): PlyosTypefaceFamily {
            return PlyosTypefaceFamily(
                normal = resources.getFont(R.font.plyos_regular),
                bold = resources.getFont(R.font.plyos_bold),
                italic = resources.getFont(R.font.plyos_italic),
                boldItalic = resources.getFont(R.font.plyos_bold_italic),
            )
        }

        /** Non-production fallback for isolated platform-layout tests without Android resources. */
        val systemSerif = PlyosTypefaceFamily(
            normal = Typeface.create(Typeface.SERIF, Typeface.NORMAL),
            bold = Typeface.create(Typeface.SERIF, Typeface.BOLD),
            italic = Typeface.create(Typeface.SERIF, Typeface.ITALIC),
            boldItalic = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC),
        )
    }
}

@Composable
fun rememberPlyosTypefaceFamily(): PlyosTypefaceFamily {
    val resources = LocalContext.current.resources
    return remember(resources) { PlyosTypefaceFamily.load(resources) }
}

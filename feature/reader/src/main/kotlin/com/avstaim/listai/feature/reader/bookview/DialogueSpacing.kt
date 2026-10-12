package com.avstaim.listai.feature.reader.bookview

/** Display-only typography: fix the gap after a reply's opening dash without shifting UTF-16 offsets. */
internal fun dialogueDisplayText(source: CharSequence): CharSequence {
    var display: StringBuilder? = null
    var lineStart = 0
    while (lineStart < source.length) {
        val lineEnd = source.indexOf('\n', lineStart).takeIf { it >= 0 } ?: source.length
        var dash = lineStart
        while (dash < lineEnd && source[dash].isDialogueSpace()) dash++
        if (dash < lineEnd && source[dash] in "—–―-") {
            val gapStart = dash + 1
            var wordStart = gapStart
            while (wordStart < lineEnd && source[wordStart].isDialogueSpace()) wordStart++
            if (wordStart > gapStart && wordStart < lineEnd) {
                for (index in gapStart until wordStart) {
                    if (source[index] == ' ' || source[index] == '\u00A0') {
                        // Use a fixed narrow NBSP on every supported API; older text
                        // engines can stretch ordinary NBSP during justification too.
                        val text = display ?: StringBuilder(source).also { display = it }
                        text.setCharAt(index, '\u202F')
                    }
                }
            }
        }
        lineStart = lineEnd + 1
    }
    return display?.toString() ?: source
}

private fun Char.isDialogueSpace() = this == ' ' || this == '\u00A0' || this == '\u202F'

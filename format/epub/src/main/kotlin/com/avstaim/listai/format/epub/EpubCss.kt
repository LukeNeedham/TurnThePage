package com.avstaim.listai.format.epub

/** Publication typography is reflowed using reader preferences; retain semantic text emphasis only. */
internal class EpubCss private constructor(private val rules: List<Rule>) {
    private data class Rule(val tag: String?, val id: String?, val classes: Set<String>, val declaration: String) {
        fun matches(tag: String, id: String?, classes: Set<String>): Boolean =
            (this.tag == null || this.tag == tag) && (this.id == null || this.id == id) && classes.containsAll(this.classes)
    }
    fun declarations(tag: String, id: String?, classes: Set<String>): String =
        rules.filter { it.matches(tag, id, classes) }.sortedBy { (if (it.id == null) 0 else 100) +
            it.classes.size * 10 + (if (it.tag == null) 0 else 1) }.joinToString(";") { it.declaration }

    companion object {
        fun parse(value: String): EpubCss {
            val text = value.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
                .replace(Regex("@(?:charset|import|namespace)\\s+[^;]*;", RegexOption.IGNORE_CASE), "")
            val rules = mutableListOf<Rule>()
            // Match bounded simple selectors. Imports, scripts, URLs, media queries, and generated
            // content are never executed; complex layout belongs to the reader's own pagination.
            var start = 0
            while (start < text.length) {
                val open = text.indexOf('{', start)
                if (open < 0) break
                var depth = 1
                var end = open + 1
                while (end < text.length && depth > 0) {
                    when (text[end++]) { '{' -> depth++; '}' -> depth-- }
                }
                if (depth != 0) break
                val selectorList = text.substring(start, open).trim()
                val declaration = text.substring(open + 1, end - 1)
                start = end
                if (selectorList.startsWith('@') || '{' in declaration) continue
                selectorList.split(',').forEach selectorLoop@ { raw ->
                    val selector = raw.trim()
                    if (!Regex("(?:[A-Za-z][A-Za-z0-9_-]*|\\*)?(?:[.#][A-Za-z0-9_-]+)*").matches(selector) || selector.isEmpty()) return@selectorLoop
                    val tag = Regex("^[A-Za-z][A-Za-z0-9_-]*").find(selector)?.value?.lowercase()
                    val ids = Regex("#([A-Za-z0-9_-]+)").findAll(selector).map { it.groupValues[1] }.toList()
                    if (ids.size > 1) return@selectorLoop
                    val classes = Regex("\\.([A-Za-z0-9_-]+)").findAll(selector).map { it.groupValues[1] }.toSet()
                    rules += Rule(tag, ids.firstOrNull(), classes, declaration)
                }
            }
            return EpubCss(rules)
        }
    }
}

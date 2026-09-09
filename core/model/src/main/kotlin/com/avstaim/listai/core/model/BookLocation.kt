package com.avstaim.listai.core.model

/**
 * A stable semantic position in the source document.
 *
 * [charOffset] is a zero-based UTF-16 code-unit index, matching Kotlin [String] indexing, within
 * the target block's [BookBlock.plainText]. [fallbackGlobalOffset] uses UTF-16 code units in the
 * parser's deterministic flattened logical flow. It is a recovery hint; [blockId] and [charOffset]
 * remain the canonical position.
 */
data class BookLocation(
    val blockId: BlockId,
    val charOffset: Int,
    val fallbackGlobalOffset: Long,
) {
    init {
        require(charOffset >= 0) { "charOffset must not be negative" }
        require(fallbackGlobalOffset >= 0L) { "fallbackGlobalOffset must not be negative" }
    }
}

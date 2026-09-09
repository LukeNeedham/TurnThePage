package com.avstaim.listai.core.model

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


package com.avstaim.listai.core.model

@JvmInline
value class BookId(val value: String) {
    init {
        require(value.isNotBlank()) { "BookId must not be blank" }
    }
}

@JvmInline
value class BlockId(val value: String) {
    init {
        require(value.isNotBlank()) { "BlockId must not be blank" }
    }
}

@JvmInline
value class ImageId(val value: String) {
    init {
        require(value.isNotBlank()) { "ImageId must not be blank" }
    }
}

@JvmInline
value class NoteId(val value: String) {
    init {
        require(value.isNotBlank() && value == value.trim() && '#' !in value) {
            "NoteId must be normalized and must not contain '#'"
        }
    }
}

@JvmInline
value class PageId(val value: Long)

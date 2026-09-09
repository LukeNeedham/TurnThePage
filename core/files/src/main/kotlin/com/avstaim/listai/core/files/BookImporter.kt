package com.avstaim.listai.core.files

import com.avstaim.listai.core.model.BookId

data class BookImportSource(
    val uri: String,
    val displayName: String?,
)

interface BookImporter {
    suspend fun import(source: BookImportSource): BookId
}


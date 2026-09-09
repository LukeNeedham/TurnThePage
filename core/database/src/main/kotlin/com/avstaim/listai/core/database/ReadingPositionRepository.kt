package com.avstaim.listai.core.database

import com.avstaim.listai.core.model.BookId
import com.avstaim.listai.core.model.BookLocation

interface ReadingPositionRepository {
    suspend fun load(bookId: BookId): BookLocation?

    suspend fun save(bookId: BookId, location: BookLocation)
}


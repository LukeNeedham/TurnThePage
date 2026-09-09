package com.avstaim.listai.format.fb2

import com.avstaim.listai.core.model.BookDocument
import java.io.InputStream

interface BookParser {
    fun supports(fileName: String): Boolean

    suspend fun parse(input: InputStream): BookDocument
}

sealed class BookParseException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class UnsupportedFormat(fileName: String) :
        BookParseException("Unsupported book format: $fileName")

    class MalformedDocument(cause: Throwable? = null) :
        BookParseException("The book document is malformed", cause)
}


package com.avstaim.listai.format.fb2

import com.avstaim.listai.core.model.BookDocument
import java.io.InputStream

interface BookParser {
    fun supports(fileName: String): Boolean

    /**
     * Parses [input] without taking ownership of it.
     *
     * [fileName] is an optional format hint. Implementations must still inspect the stream so a
     * ZIP archive with an unusual name cannot accidentally be parsed as XML (and vice versa).
     */
    suspend fun parse(input: InputStream, fileName: String? = null): BookDocument
}

sealed class BookParseException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class UnsupportedFormat(fileName: String) :
        BookParseException("Unsupported book format: $fileName")

    class MalformedDocument(cause: Throwable? = null) :
        BookParseException("The book document is malformed", cause)

    class EncryptedDocument(cause: Throwable? = null) :
        BookParseException("Password-protected book documents are not supported", cause)

    class ArchiveContainsNoFb2 :
        BookParseException("The archive does not contain an FB2 document")

    class NestedArchive :
        BookParseException("Nested archives are not supported")

    class MissingTitle :
        BookParseException("The FB2 document does not contain a book title")

    class MissingReadableBody :
        BookParseException("The FB2 document does not contain a readable body")

    class ResourceLimitExceeded(
        val resource: String,
        val limit: Long,
    ) : BookParseException("The $resource exceeds the configured limit of $limit")
}

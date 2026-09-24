package com.avstaim.listai.core.files

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class BookFormatsTest {
    @Test fun detectsOriginalBytesWithoutTrustingExtension() {
        val file = File.createTempFile("book-format", ".book")
        try {
            file.writeText("%PDF-1.4\n")
            assertEquals(BookFormat.PDF, BookFormat.detect(file))
            file.writeText("<FictionBook><p>PDF signature: %PDF-1.7</p></FictionBook>")
            assertEquals(BookFormat.FB2, BookFormat.detect(file))
            ZipOutputStream(file.outputStream()).use { it.putNextEntry(ZipEntry("META-INF/container.xml")); it.write(1); it.closeEntry() }
            assertEquals(BookFormat.EPUB, BookFormat.detect(file))
            ZipOutputStream(file.outputStream()).use { it.putNextEntry(ZipEntry("book.fb2")); it.write(1); it.closeEntry() }
            assertEquals(BookFormat.FB2_ZIP, BookFormat.detect(file))
        } finally { file.delete() }
    }

    @Test fun folderFiltersSupportedFilesAndUsesProviderMimeForExtensionlessBooks() {
        assertTrue(BookFormat.accepts("BOOK.EPUB", null))
        assertTrue(BookFormat.accepts("a.FB2.ZIP", "application/octet-stream"))
        assertTrue(BookFormat.accepts("book", "application/pdf"))
        assertFalse(BookFormat.accepts("photo.jpg", "image/jpeg"))
        assertFalse(BookFormat.accepts("notes.txt", "text/plain"))
    }
}

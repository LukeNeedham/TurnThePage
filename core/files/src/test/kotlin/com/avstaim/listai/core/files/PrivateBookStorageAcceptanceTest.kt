package com.avstaim.listai.core.files

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateBookStorageAcceptanceTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun storage() = PrivateBookStorage(File(temporary.root, "private-books"))

    @Test fun deletingImportedCopyLeavesTheOriginalFileUntouched() {
        val original = temporary.newFile("original.fb2").apply {
            writeText("<FictionBook>Белые страницы 🌲</FictionBook>")
        }
        val bytes = original.readBytes()
        val storage = storage()
        val staged = original.inputStream().use { storage.stage(it) }
        val imported = storage.promote(staged, "book.book")
        assertArrayEquals(bytes, imported.readBytes())
        assertFalse(staged.exists())

        storage.remove(imported.name)

        assertFalse(imported.exists())
        assertTrue(original.isFile)
        assertArrayEquals(bytes, original.readBytes())
    }

    @Test fun failedOrOversizedCopyLeavesNoPartialBook() {
        val storage = storage()
        val failingSource = object : InputStream() {
            var copied = false
            override fun read(): Int = throw IOException("Provider became unavailable")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (copied) throw IOException("Provider became unavailable")
                copied = true
                buffer[offset] = 42
                return 1
            }
        }
        assertThrows(IOException::class.java) { storage.stage(failingSource) }
        assertTrue(storage.directory.listFiles().orEmpty().isEmpty())

        assertThrows(ImportTooLargeException::class.java) {
            storage.stage(ByteArrayInputStream(ByteArray(33)), maxBytes = 32)
        }
        assertTrue(storage.directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun interruptionBeforeCopyLeavesNoPartialBook() {
        val storage = storage()
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) {
                storage.stage(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            }
        } finally {
            Thread.interrupted() // Never leak an interrupt flag into the Gradle test worker.
        }
        assertTrue(storage.directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun publicationCannotOverwriteAnExistingBookOrMoveAnExternalSource() {
        val storage = storage()
        val existing = storage.promote(storage.stage(ByteArrayInputStream(byteArrayOf(1))), "existing.book")
        val staged = storage.stage(ByteArrayInputStream(byteArrayOf(2)))
        assertThrows(IOException::class.java) { storage.promote(staged, existing.name) }
        assertArrayEquals(byteArrayOf(1), existing.readBytes())
        assertTrue(staged.isFile)

        val external = temporary.newFile("external.part").apply { writeText("Original") }
        assertThrows(IllegalStateException::class.java) { storage.promote(external, "other.book") }
        assertEquals("Original", external.readText())
        assertFalse(storage.file("other.book").exists())
        assertThrows(IllegalArgumentException::class.java) { storage.remove("../external.part") }
        assertTrue(external.exists())
    }

    @Test fun recoveryRetainsPublishedBooksAndOnlyRemovesUnreferencedPrivateFiles() {
        val storage = storage()
        val external = temporary.newFile("keep.fb2").apply { writeText("External source") }
        val published = storage.promote(storage.stage(ByteArrayInputStream(byteArrayOf(1))), "keep.book")
        val incomplete = storage.stage(ByteArrayInputStream(byteArrayOf(2)))
        val unrecorded = storage.promote(storage.stage(ByteArrayInputStream(byteArrayOf(3))), "orphan.book")

        storage.reconcile(setOf(published.name))

        assertArrayEquals(byteArrayOf(1), published.readBytes())
        assertFalse(incomplete.exists())
        assertFalse(unrecorded.exists())
        assertEquals("External source", external.readText())
        assertEquals(listOf(published.name), storage.directory.listFiles().orEmpty().map { it.name })
    }
}

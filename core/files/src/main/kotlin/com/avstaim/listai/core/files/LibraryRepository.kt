package com.avstaim.listai.core.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.avstaim.listai.core.database.AppPreferences
import com.avstaim.listai.core.database.BookEntity
import com.avstaim.listai.core.database.BookmarkEntity
import com.avstaim.listai.core.database.LibraryDao
import com.avstaim.listai.core.database.StoredProgress
import com.avstaim.listai.core.model.*
import com.avstaim.listai.format.fb2.BookParser
import com.avstaim.listai.format.fb2.Fb2BookParser
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class OpenedBook(val book: LibraryBook, val document: BookDocument)

/** All filesystem work and Room calls happen off the rendering path. Mutations are serialized. */
class LibraryRepository(
    private val dao: LibraryDao,
    private val storage: PrivateBookStorage,
    private val preferences: AppPreferences,
    private val parser: BookParser = Fb2BookParser(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val operations = Mutex()
    val books = dao.observeBooks().map { rows -> rows.map(::toModel) }

    fun bookmarks(id: BookId) = dao.observeBookmarks(id.value).map { rows -> rows.map { it.toModel() } }

    suspend fun initialize() = withContext(Dispatchers.IO) { operations.withLock {
        // Complete interrupted deletion before removing unpublished files from interrupted imports.
        dao.allBooks().filter { it.deleting }.forEach { finishDelete(it) }
        val rows = dao.allBooks()
        rows.filter { !storage.file(it.fileName).isFile }.forEach {
            dao.markDeleting(it.id)
            finishDelete(it)
        }
        val remaining = dao.allBooks()
        storage.reconcile(remaining.flatMap { listOfNotNull(it.fileName, it.coverName) }.toSet())
    } }

    suspend fun import(resolver: ContentResolver, uri: Uri): BookId {
        // The copied file remains usable after a provider revokes its URI grant.
        val name = withContext(Dispatchers.IO) {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }
        return import(name) { resolver.openInputStream(uri) ?: throw FileNotFoundException("Unavailable book") }
    }

    suspend fun import(name: String?, openSource: () -> InputStream): BookId = withContext(Dispatchers.IO) {
        operations.withLock {
            var staged: File? = null
            try {
                // Install the cleanup reference inside runInterruptible: cancellation can discard
                // its return value even after the copy finishes.
                runInterruptible { openSource().use { staged = storage.stage(it) } }
                val document = requireNotNull(staged).inputStream().use { parser.parse(it, name) }
                currentCoroutineContext().ensureActive()
                val existing = dao.book(document.id.value)
                if (existing != null && !existing.deleting && storage.file(existing.fileName).isFile) {
                    return@withLock document.id
                }
                if (existing != null) finishDelete(existing)
                val stem = document.id.value.removePrefix("sha256:")
                val fileName = "$stem.book"
                val coverName = "$stem.cover.jpg"
                // A process death between rename and INSERT leaves only unpublished owned files;
                // initialize() removes them. Cancellation cannot split this small commit section.
                withContext(NonCancellable) {
                    var published = false
                    var promoted = false
                    try {
                        storage.promote(requireNotNull(staged), fileName)
                        promoted = true
                        val hasCover = writeCover(document, storage.file(coverName))
                        check(dao.insert(BookEntity(document.id.value, document.metadata.title,
                            document.metadata.authors.joinToString(", "), fileName,
                            coverName.takeIf { hasCover }, clock(), totalCharacters = document.readingLength(),
                            progress = StoredProgress(cover = CoverPosition.FRONT.name))) != -1L)
                        published = true
                    } finally {
                        if (!published && promoted) {
                            storage.remove(fileName)
                            storage.remove(coverName)
                        }
                    }
                }
                document.id
            } finally {
                staged?.let { if (it.exists()) storage.remove(it.name) }
            }
        }
    }

    suspend fun open(id: BookId): OpenedBook = withContext(Dispatchers.IO) { operations.withLock {
        val row = requireNotNull(dao.book(id.value)?.takeUnless { it.deleting }) { "Book was removed" }
        val document = storage.file(row.fileName).inputStream().use { parser.parse(it) }
        check(document.id == id) { "Book contents changed" }
        val openedAt = clock()
        dao.markOpened(id.value, openedAt)
        preferences.selectBook(id.value)
        OpenedBook(toModel(row.copy(lastOpenedAt = openedAt)), document)
    } }

    suspend fun saveProgress(id: BookId, value: ReadingProgress) {
        // A long copy/parse must not delay the active reader's durable checkpoints.
        // This single UPDATE cannot recreate deleted/tombstoned books and does not touch files.
        dao.saveProgress(id.value, value.location?.blockId?.value, value.location?.charOffset ?: 0,
            value.location?.fallbackGlobalOffset ?: 0, value.cover.name, value.fraction,
            value.tocEntryIndex, value.tocCharOffset,
            value.annotationLocation?.blockId?.value, value.annotationLocation?.charOffset ?: 0,
            value.annotationLocation?.fallbackGlobalOffset ?: 0)
    }

    suspend fun delete(id: BookId) = withContext(Dispatchers.IO) { operations.withLock {
        val row = dao.book(id.value) ?: return@withLock
        withContext(NonCancellable) {
            dao.markDeleting(id.value)
            finishDelete(row)
        }
    } }

    private suspend fun finishDelete(row: BookEntity) {
        storage.remove(row.fileName)
        row.coverName?.let(storage::remove)
        preferences.clearBookIfSelected(row.id)
        dao.delete(row.id) // Foreign key cascade removes all bookmarks atomically.
    }

    suspend fun addBookmark(id: BookId, location: BookLocation, snippet: String) = operations.withLock {
        if (dao.book(id.value)?.deleting != false) return@withLock
        dao.insertBookmark(BookmarkEntity(UUID.randomUUID().toString(), id.value, location.blockId.value,
            location.charOffset, location.fallbackGlobalOffset, clock(), snippet.take(160)))
    }

    suspend fun deleteBookmark(bookId: BookId, bookmarkId: String) = operations.withLock {
        dao.deleteBookmark(bookId.value, bookmarkId)
    }

    private fun toModel(row: BookEntity) = LibraryBook(BookId(row.id), row.title, row.authors,
        row.fileName, row.coverName?.let { storage.file(it).absolutePath }, row.importedAt,
        row.lastOpenedAt, row.totalCharacters, row.progress.toModel())
}

/** Decode only a thumbnail; malformed/oversize images simply use the library placeholder. */
private fun writeCover(document: BookDocument, output: File): Boolean {
    val bitmap = try {
        val bytes = document.metadata.coverImageId?.let { document.imagesById[it]?.content?.load() } ?: return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
    } catch (_: RuntimeException) {
        return false // Optional corrupt image; file-write failures below still abort publication.
    }
    return try {
        FileOutputStream(output).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream))
            stream.fd.sync()
        }
        true
    } finally { bitmap.recycle() }
}

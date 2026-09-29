package com.avstaim.listai.core.files

import android.content.Context
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
import com.avstaim.listai.core.database.CatalogBookRow
import com.avstaim.listai.core.database.ShelfTopology
import com.avstaim.listai.core.database.validatedStackName
import com.avstaim.listai.core.model.*
import com.avstaim.listai.format.fb2.BookParser
import com.avstaim.listai.format.epub.EpubBookParser
import com.avstaim.listai.format.pdf.PdfBookParser
import com.avstaim.listai.format.fb2.Fb2BookParser
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SharedBook(val file: File, val mimeType: String)

data class OpenedBook(val book: LibraryBook, val document: BookDocument)

/** All filesystem work and Room calls happen off the rendering path. Mutations are serialized. */
class LibraryRepository(
    private val context: Context,
    private val dao: LibraryDao,
    private val storage: PrivateBookStorage,
    private val preferences: AppPreferences,
    private val parser: BookParser = Fb2BookParser(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val operations = Mutex()
    val catalog = dao.observeCatalog().map(::toCatalog)
    val books = catalog.map { it.books }
    private var undoTopology: ShelfTopology? = null
    private val mutableCanUndo = MutableStateFlow(false)
    val canUndo = mutableCanUndo.asStateFlow()

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
                val document = parseFile(requireNotNull(staged), name)
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
                        val hasCover = writeCover(document, storage.file(coverName))
                        storage.promote(requireNotNull(staged), fileName)
                        promoted = true
                        check(dao.insert(BookEntity(document.id.value, document.metadata.title,
                            document.metadata.authors.joinToString(", "), fileName,
                            coverName.takeIf { hasCover }, clock(), totalCharacters = document.readingLength(),
                            progress = StoredProgress(cover = CoverPosition.FRONT.name),
                            shelfOrder = (dao.firstShelfPosition() ?: 0L) - 1L,
                            authorNamesJson = encodeMetadata(document.metadata.authors),
                            series = document.metadata.sequence?.name,
                            genresJson = encodeMetadata(document.metadata.genres), metadataState = 1)) != -1L)
                        published = true
                    } finally {
                        if (!published) {
                            if (promoted) storage.remove(fileName)
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
        val document = parseFile(storage.file(row.fileName), null, pdfTitle = "${row.title}.pdf")
        check(document.id == id) { "Book contents changed" }
        val openedAt = clock()
        dao.markOpened(id.value, openedAt)
        preferences.selectBook(id.value)
        OpenedBook(toModel(row.copy(lastOpenedAt = openedAt)), document)
    } }

    private suspend fun parseFile(file: File, name: String?, pdfTitle: String? = name): BookDocument = when (BookFormat.detect(file)) {
        BookFormat.EPUB -> file.inputStream().use { EpubBookParser().parse(it, name) }
        BookFormat.PDF -> PdfBookParser(context.applicationContext).parse(file, pdfTitle).withPdfCover(file)
        BookFormat.FB2, BookFormat.FB2_ZIP -> file.inputStream().use { parser.parse(it, name) }
    }

    suspend fun moveBook(id: BookId, targetId: BookId) = mutateShelf { topology ->
        val stack = topology.stacks.firstOrNull { id.value in it.bookIds && targetId.value in it.bookIds }
        if (stack == null) topology.moveShelfItem(ShelfTopology.bookKey(id.value), ShelfTopology.bookKey(targetId.value))
        else topology.moveStackBook(stack.id, id.value, targetId.value)
    }

    suspend fun createStack(name: String, bookIds: List<BookId>, requestedId: String? = null): String {
        validatedStackName(name)
        val id = requestedId ?: UUID.randomUUID().toString()
        require(id.isNotBlank()) { "A stack needs an ID" }
        mutateShelf { topology ->
            require(bookIds.distinct().count { it.value in topology.books } >= 2) { "A stack needs two books" }
            topology.createStack(id, name, bookIds.map { it.value })
        }
        return id
    }

    suspend fun renameStack(stackId: String, name: String) = mutateShelf { it.renameStack(stackId, name) }
    suspend fun addToStack(stackId: String, bookIds: List<BookId>, onTop: Boolean = false) =
        mutateShelf { it.addToStack(stackId, bookIds.map(BookId::value), onTop) }
    suspend fun extractFromStack(bookIds: List<BookId>) = mutateShelf { it.extract(bookIds.map(BookId::value)) }
    suspend fun dissolveStack(stackId: String) = mutateShelf { it.dissolve(stackId) }
    suspend fun moveShelfItem(key: String, targetKey: String) = mutateShelf { it.moveShelfItem(key, targetKey) }
    suspend fun moveStackBook(stackId: String, bookId: BookId, targetBookId: BookId) =
        mutateShelf { it.moveStackBook(stackId, bookId.value, targetBookId.value) }

    private suspend fun mutateShelf(transform: (ShelfTopology) -> ShelfTopology) = withContext(Dispatchers.IO) {
        operations.withLock {
            var changed = false
            val previous = dao.changeShelf { before -> transform(before).also { changed = it != before } }
            if (changed) { undoTopology = previous; mutableCanUndo.value = true }
        }
    }

    suspend fun undoLastLibraryChange(): Boolean = withContext(Dispatchers.IO) {
        operations.withLock {
            val previous = undoTopology ?: return@withLock false
            dao.changeShelf { current -> previous.restoreAgainst(current) }
            undoTopology = null
            mutableCanUndo.value = false
            true
        }
    }

    suspend fun dismissLibraryUndo() = operations.withLock {
        undoTopology = null
        mutableCanUndo.value = false
    }

    /** Each call processes a bounded batch after startup; cancelled rows remain pending on disk.
     * Parsing stays outside the mutation lock so imports and reading are still responsive.
     * The application may repeat this until zero is returned, yielding between batches. */
    suspend fun backfillMetadata(batchSize: Int = 8): Int = withContext(Dispatchers.IO) {
        require(batchSize in 1..32)
        val pending = dao.pendingMetadata(batchSize)
        for (row in pending) {
            currentCoroutineContext().ensureActive()
            try {
                val source = storage.file(row.fileName)
                // Fixed-layout files have no supported series/genre metadata; no rendering is needed.
                val metadata = if (BookFormat.detect(source) == BookFormat.PDF) null
                    else parseFile(source, null).also { check(it.id.value == row.id) }.metadata
                currentCoroutineContext().ensureActive()
                operations.withLock {
                    if (dao.book(row.id)?.let { !it.deleting && it.fileName == row.fileName && it.metadataState == 0 } == true) {
                        dao.saveMetadata(row.id, encodeMetadata(metadata?.authors.orEmpty()), metadata?.sequence?.name,
                            encodeMetadata(metadata?.genres.orEmpty()), 1)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                dao.markMetadataUnreadable(row.id)
            }
        }
        pending.size
    }

    /** A separate read-only sharing copy remains valid even if the shelf book is deleted. */
    suspend fun prepareShare(id: BookId, cacheDirectory: File): SharedBook = withContext(Dispatchers.IO) {
        operations.withLock {
            val row = requireNotNull(dao.book(id.value)?.takeUnless { it.deleting }) { "Book was removed" }
            val source = storage.file(row.fileName)
            val format = BookFormat.detect(source)
            val directory = File(cacheDirectory, row.fileName.removeSuffix(".book"))
            check(directory.isDirectory || directory.mkdirs())
            val title = row.title.replace(Regex("[^\\p{L}\\p{N} ._()-]"), "_").trim().take(60).trimEnd { Character.isHighSurrogate(it) }.ifBlank { "book" }
            val output = File(directory, "$title.${format.extension}")
            val temporary = File(directory, "share.part")
            try {
                runInterruptible { source.inputStream().use { input ->
                    FileOutputStream(temporary).use { stream -> input.copyTo(stream); stream.fd.sync() }
                } }
                currentCoroutineContext().ensureActive()
                check(temporary.renameTo(output)) { "Cannot prepare sharing copy" }
            } finally { temporary.delete() }
            SharedBook(output, format.mimeType)
        }
    }

    suspend fun saveProgress(id: BookId, value: ReadingProgress) {
        // A long copy/parse must not delay the active reader's durable checkpoints.
        // This single UPDATE cannot recreate deleted/tombstoned books and does not touch files.
        dao.saveProgress(id.value, value.location?.blockId?.value, value.location?.charOffset ?: 0,
            value.location?.fallbackGlobalOffset ?: 0, value.cover.name, value.fraction,
            value.tocEntryIndex, value.tocCharOffset,
            value.annotationLocation?.blockId?.value, value.annotationLocation?.charOffset ?: 0,
            value.annotationLocation?.fallbackGlobalOffset ?: 0,
            value.titleLocation?.blockId?.value, value.titleLocation?.charOffset ?: 0,
            value.titleLocation?.fallbackGlobalOffset ?: 0)
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
        row.lastOpenedAt, row.totalCharacters, row.progress.toModel(),
        authorNames = decodeMetadata(row.authorNamesJson), series = row.series,
        genres = decodeMetadata(row.genresJson), stackId = row.stackId)

    private fun toCatalog(rows: List<CatalogBookRow>): LibraryCatalog {
        val models = rows.associate { it.book.id to toModel(it.book) }
        val stackRows = rows.filter { it.book.stackId != null && it.stackName != null }.groupBy { it.book.stackId!! }
        val stacks = stackRows.map { (id, members) -> LibraryStack(id, members.first().stackName!!,
            members.sortedWith(compareBy<CatalogBookRow> { it.book.stackOrder }.thenBy { it.book.id }).map { BookId(it.book.id) }) }
        val shelf = (rows.filter { it.book.stackId == null }.map {
            Triple<LibraryShelfItem, Long, Long>(LibraryShelfItem.Book(BookId(it.book.id)), it.book.shelfOrder, it.book.importedAt)
        } + stackRows.map { (id, members) -> Triple<LibraryShelfItem, Long, Long>(
            LibraryShelfItem.Stack(id), members.first().stackShelfOrder ?: 0L, Long.MIN_VALUE) })
            .sortedWith(compareBy<Triple<LibraryShelfItem, Long, Long>> { it.second }.thenByDescending { it.third }.thenBy { it.first.key })
            .map { it.first }
        val stackById = stacks.associateBy { it.id }
        val ordered = shelf.flatMap { item -> when (item) {
            is LibraryShelfItem.Book -> listOfNotNull(models[item.bookId.value])
            is LibraryShelfItem.Stack -> stackById[item.stackId]?.bookIds.orEmpty().mapNotNull { models[it.value] }
        } }
        return LibraryCatalog(ordered, stacks, shelf)
    }
}

private fun encodeMetadata(values: List<String>): String = JSONArray(values).toString()
private fun decodeMetadata(value: String): List<String> = try {
    val json = JSONArray(value)
    (0 until json.length()).mapNotNull { json.optString(it).takeIf(String::isNotBlank) }
} catch (_: Exception) { emptyList() }

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

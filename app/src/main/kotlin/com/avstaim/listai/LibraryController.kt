package com.avstaim.listai

import android.content.Context
import android.content.Intent
import android.content.ClipData
import androidx.core.content.FileProvider
import com.avstaim.listai.core.files.visitBookFolder
import com.avstaim.listai.core.files.FolderTooLargeException
import java.io.File
import android.net.Uri
import com.avstaim.listai.core.database.AppPreferences
import com.avstaim.listai.core.files.ImportTooLargeException
import com.avstaim.listai.core.files.LibraryRepository
import com.avstaim.listai.core.files.OpenedBook
import com.avstaim.listai.core.model.*
import com.avstaim.listai.format.fb2.BookParseException
import com.avstaim.listai.feature.library.LibraryCommand
import java.io.FileNotFoundException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class ImportProgress(val completed: Int = 0, val currentName: String? = null)
data class ImportSummary(val added: Int, val duplicates: Int, val failed: Int, val skipped: Int, val cancelled: Boolean)

data class ReaderSelection(val opened: OpenedBook, val initialProgress: ReadingProgress, val session: Long)

/** Application-lifetime commands survive composition disposal and Activity recreation. */
class LibraryController(
    private val context: Context,
    val repository: LibraryRepository,
    private val preferences: AppPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var serial = 0L
    private var openRequest = 0L
    private var checkpoint: ReadingProgress? = null
    private var importJob: Job? = null
    private var undoExpiryJob: Job? = null
    private val _selection = MutableStateFlow<ReaderSelection?>(null)
    val selection = _selection.asStateFlow()
    val readerPreferences = preferences.reader.stateIn(scope, SharingStarted.Eagerly, ReaderPreferences())
    val readerOnboardingCompleted = preferences.readerOnboardingCompleted
        .map<Boolean, Boolean?> { it }.stateIn(scope, SharingStarted.Eagerly, null)
    val books = repository.books.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val catalog = repository.catalog.stateIn(scope, SharingStarted.WhileSubscribed(5_000), LibraryCatalog())
    val shelfZoom = preferences.shelfZoom.stateIn(scope, SharingStarted.Eagerly, ShelfZoom.MEDIUM)
    val canUndoLibraryChange = repository.canUndo.stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)
    private val _busy = MutableStateFlow(true)
    val busy = _busy.asStateFlow()
    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()
    private val _importProgress = MutableStateFlow(ImportProgress())
    val importProgress = _importProgress.asStateFlow()
    private val _importSummary = MutableStateFlow<ImportSummary?>(null)
    val importSummary = _importSummary.asStateFlow()
    fun dismissImportSummary() { _importSummary.value = null }
    private val _error = MutableStateFlow<Int?>(null)
    val error = _error.asStateFlow()

    init {
        scope.launch {
            for (command in commands) {
                try { command() } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { _error.value = failure.userMessage() }
            }
        }
        enqueue {
            try {
                repository.initialize()
                scope.launch(Dispatchers.IO) {
                    while (isActive && repository.backfillMetadata() > 0) yield()
                }
                if (!preferences.initialized.first()) {
                    val sample = repository.import("stage4_reading_sample.fb2") {
                        context.resources.openRawResource(R.raw.stage4_reading_sample)
                    }
                    preferences.selectBook(sample.value)
                    preferences.markInitialized()
                }
                preferences.lastBook.first()?.let { id ->
                    try { select(BookId(id)) } catch (failure: Exception) {
                        preferences.clearBookIfSelected(id)
                        throw failure
                    }
                }
            } finally { _busy.value = false }
        }
    }

    fun dismissError() { _error.value = null }
    fun setReaderPreferences(value: ReaderPreferences) = enqueue { preferences.setReaderPreferences(value) }
    fun setShelfZoom(value: ShelfZoom) = enqueue { preferences.setShelfZoom(value) }
    fun undoLibraryChange() = enqueue {
        undoExpiryJob?.cancel()
        repository.undoLastLibraryChange()
    }
    fun libraryCommand(command: LibraryCommand) = enqueue {
        when (command) {
            is LibraryCommand.CreateStack -> repository.createStack(command.name, command.bookIds)
            is LibraryCommand.RenameStack -> repository.renameStack(command.stackId, command.name)
            is LibraryCommand.AddToStack -> repository.addToStack(command.stackId, command.bookIds)
            is LibraryCommand.ExtractFromStack -> repository.extractFromStack(command.bookIds)
            is LibraryCommand.DissolveStack -> repository.dissolveStack(command.stackId)
            is LibraryCommand.MoveShelfItem -> repository.moveShelfItem(command.key, command.targetKey)
            is LibraryCommand.MoveStackBook -> repository.moveStackBook(command.stackId, command.bookId, command.targetBookId)
        }
        undoExpiryJob?.cancel()
        undoExpiryJob = scope.launch {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            val timeout = if (android.os.Build.VERSION.SDK_INT >= 29) manager.getRecommendedTimeoutMillis(
                8_000, android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT or
                    android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS) else 8_000
            delay(timeout.toLong())
            repository.dismissLibraryUndo()
        }
    }
    fun completeReaderOnboarding() = enqueue { preferences.completeReaderOnboarding() }

    fun returnToShelf() {
        // A back press during parsing invalidates its eventual selection as well.
        openRequest++
        val selectedId = _selection.value?.opened?.book?.id
        val shelfProgress = checkpoint?.resetRearCoverForShelf()
        if (selectedId != null && shelfProgress != null) {
            // A finished physical book returns to its default pose instead of reopening on the
            // notes tail or the inner back cover.
            enqueue { repository.saveProgress(selectedId, shelfProgress) }
        }
        _selection.value = null
        checkpoint = null
        // Explicitly shelving a book is durable; a process restart restores the shelf.
        enqueue { preferences.selectBook(null) }
    }
    fun open(id: BookId) {
        val request = ++openRequest
        enqueue {
            if (request != openRequest) return@enqueue
            _busy.value = true
            try { select(id) { request == openRequest } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (request == openRequest) throw failure }
            finally { _busy.value = false }
        }
    }

    private suspend fun select(id: BookId, stillRequested: () -> Boolean = { true }) {
        val opened = repository.open(id)
        if (!stillRequested()) return
        checkpoint = opened.book.progress
        _selection.value = ReaderSelection(opened, opened.book.progress, ++serial)
    }

    fun import(uri: Uri, flags: Int = 0) = importBooks(listOf(uri), flags)

    fun importBooks(uris: List<Uri>, flags: Int = 0) = startImport(uris.distinct(), null, flags)

    fun importFolder(uri: Uri) = startImport(emptyList(), uri, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

    private fun startImport(uris: List<Uri>, folder: Uri?, flags: Int) {
        if (uris.isEmpty() && folder == null) return
        if (importJob?.isActive == true) {
            _error.value = R.string.library_import_in_progress
            return
        }
        _importing.value = true
        _importProgress.value = ImportProgress()
        _importSummary.value = null
        importJob = scope.launch {
            val grants = mutableListOf<Uri>()
            var added = 0
            var duplicates = 0
            var failed = 0
            var skipped = 0
            var cancelled = false
            var singleId: BookId? = null
            var initialIds: Set<BookId>? = null
            val batch = folder != null || uris.size > 1
            suspend fun acquire(uri: Uri) {
                if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 &&
                    context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) {
                    try {
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        grants += uri
                    } catch (_: SecurityException) { /* Temporary incoming grant still permits copying. */ }
                }
            }
            try {
                val known = repository.books.first().mapTo(mutableSetOf()) { it.id }
                initialIds = known.toSet()
                suspend fun copy(uri: Uri) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val id = repository.import(context.contentResolver, uri)
                        if (known.add(id)) added++ else duplicates++
                        singleId = id
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) {
                        failed++
                        if (!batch) _error.value = failure.userMessage()
                    }
                    _importProgress.value = ImportProgress(added + duplicates + failed)
                }
                if (folder != null) {
                    acquire(folder)
                    visitBookFolder(context.contentResolver, folder,
                        onBook = { uri -> copy(uri) }, onUnreadableFolder = { failed++ },
                        onSkippedFile = { skipped++ })
                } else {
                    for (uri in uris) {
                        acquire(uri)
                        try { copy(uri) } finally {
                            if (grants.remove(uri)) releaseGrant(uri)
                        }
                    }
                }
                if (!batch && failed == 0) singleId?.let(::open)
            } catch (cancel: CancellationException) {
                cancelled = true
                throw cancel
            } catch (failure: Exception) {
                failed++
                _error.value = failure.userMessage()
            } finally {
                // Cancellation may arrive inside the non-cancellable publication section.
                // Count durable copies rather than losing that last successfully committed book.
                if (cancelled && initialIds != null) withContext(NonCancellable) {
                    added = repository.books.first().count { it.id !in requireNotNull(initialIds) }
                }
                grants.forEach(::releaseGrant)
                _importing.value = false
                if (batch || cancelled) _importSummary.value = ImportSummary(added, duplicates, failed, skipped, cancelled)
            }
        }
    }

    private fun releaseGrant(uri: Uri) {
        try { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { /* Grant may already have been revoked. */ }
    }

    fun moveBook(id: BookId, targetId: BookId) = enqueue { repository.moveBook(id, targetId) }

    fun share(id: BookId) = enqueue {
        val shared = repository.prepareShare(id, File(context.cacheDir, "shared_books"))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.books", shared.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = shared.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(shared.file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.library_share_title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun cancelImport() { importJob?.cancel() }

    fun progressChanged(id: BookId, session: Long, progress: ReadingProgress) {
        if (_selection.value?.opened?.book?.id != id || _selection.value?.session != session || checkpoint == progress) return
        checkpoint = progress
        _selection.value = requireNotNull(_selection.value).copy(initialProgress = progress)
        enqueue { repository.saveProgress(id, progress) }
    }

    fun saveOnBackground() {
        val id = _selection.value?.opened?.book?.id ?: return
        val value = checkpoint ?: return
        enqueue { repository.saveProgress(id, value) }
    }

    fun delete(id: BookId) {
        if (_selection.value?.opened?.book?.id == id) {
            _selection.value = null
            checkpoint = null
        }
        enqueue {
            repository.delete(id)
            if (_selection.value?.opened?.book?.id == id) {
                _selection.value = null
                checkpoint = null
            }
        }
    }

    fun addBookmark() {
        if (checkpoint?.tocEntryIndex != null || checkpoint?.annotationLocation != null || checkpoint?.titleLocation != null ||
            checkpoint?.cover != CoverPosition.OPEN) return
        val location = checkpoint?.location ?: return
        addBookmarkAt(location)
    }

    fun addBookmarkAt(location: BookLocation) {
        val current = _selection.value ?: return
        if (checkpoint?.tocEntryIndex != null || checkpoint?.annotationLocation != null || checkpoint?.titleLocation != null ||
            checkpoint?.cover != CoverPosition.OPEN) return
        enqueue {
            val snippet = withContext(Dispatchers.Default) { current.opened.document.bookmarkSnippet(location) }
            repository.addBookmark(current.opened.book.id, location, snippet)
        }
    }

    fun jump(bookmark: Bookmark) {
        val current = _selection.value?.takeIf { it.opened.book.id == bookmark.bookId } ?: return
        val fraction = (bookmark.location.fallbackGlobalOffset.toDouble() /
            current.opened.book.totalCharacters.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
        val next = ReadingProgress(bookmark.location, CoverPosition.OPEN, fraction)
        checkpoint = next
        _selection.value = current.copy(initialProgress = next, session = ++serial)
        enqueue { repository.saveProgress(bookmark.bookId, next) }
    }

    fun deleteBookmark(bookmark: Bookmark) = enqueue { repository.deleteBookmark(bookmark.bookId, bookmark.id) }

    private fun enqueue(command: suspend () -> Unit) { check(commands.trySend(command).isSuccess) }
}

internal fun ReadingProgress.resetRearCoverForShelf(): ReadingProgress = when (cover) {
    CoverPosition.BACK, CoverPosition.BOUNDARY -> copy(cover = CoverPosition.FRONT)
    CoverPosition.OPEN, CoverPosition.FRONT -> this
}

private fun Exception.userMessage(): Int = when (this) {
    is SecurityException, is FileNotFoundException -> R.string.library_error_access
    is ImportTooLargeException, is FolderTooLargeException, is BookParseException.ResourceLimitExceeded -> R.string.library_error_too_large
    is BookParseException.ArchiveContainsNoFb2 -> R.string.library_error_empty_zip
    is BookParseException.EncryptedDocument -> R.string.library_error_encrypted
    is BookParseException -> R.string.library_error_invalid
    else -> R.string.library_error_storage
}

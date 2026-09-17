package com.avstaim.listai

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.avstaim.listai.core.database.AppPreferences
import com.avstaim.listai.core.files.ImportTooLargeException
import com.avstaim.listai.core.files.LibraryRepository
import com.avstaim.listai.core.files.OpenedBook
import com.avstaim.listai.core.model.*
import com.avstaim.listai.format.fb2.BookParseException
import java.io.FileNotFoundException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

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
    private val _selection = MutableStateFlow<ReaderSelection?>(null)
    val selection = _selection.asStateFlow()
    val readerPreferences = preferences.reader.stateIn(scope, SharingStarted.Eagerly, ReaderPreferences())
    val readerOnboardingCompleted = preferences.readerOnboardingCompleted
        .map<Boolean, Boolean?> { it }.stateIn(scope, SharingStarted.Eagerly, null)
    val books = repository.books.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _busy = MutableStateFlow(true)
    val busy = _busy.asStateFlow()
    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()
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
    fun completeReaderOnboarding() = enqueue { preferences.completeReaderOnboarding() }

    fun returnToShelf() {
        // A back press during parsing invalidates its eventual selection as well.
        openRequest++
        saveOnBackground()
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

    fun import(uri: Uri, flags: Int = 0) {
        if (importJob?.isActive == true) {
            _error.value = R.string.library_import_in_progress
            return
        }
        _importing.value = true
        importJob = scope.launch {
            var acquiredGrant = false
            try {
                if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 &&
                    context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) {
                    try {
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        acquiredGrant = true
                    } catch (_: SecurityException) { /* Copy using the temporary incoming grant. */ }
                }
                val id = repository.import(context.contentResolver, uri)
                open(id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _error.value = failure.userMessage() }
            finally {
                if (acquiredGrant) {
                    try { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    catch (_: SecurityException) { /* The provider may already have revoked it. */ }
                }
                _importing.value = false
            }
        }
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
        val current = _selection.value ?: return
        if (checkpoint?.tocEntryIndex != null || checkpoint?.annotationLocation != null ||
            checkpoint?.cover != CoverPosition.OPEN) return
        val location = checkpoint?.location ?: return
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

private fun Exception.userMessage(): Int = when (this) {
    is SecurityException, is FileNotFoundException -> R.string.library_error_access
    is ImportTooLargeException, is BookParseException.ResourceLimitExceeded -> R.string.library_error_too_large
    is BookParseException.ArchiveContainsNoFb2 -> R.string.library_error_empty_zip
    is BookParseException -> R.string.library_error_invalid
    else -> R.string.library_error_storage
}

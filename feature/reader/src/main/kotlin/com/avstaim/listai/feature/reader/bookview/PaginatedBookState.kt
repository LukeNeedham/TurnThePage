package com.avstaim.listai.feature.reader.bookview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalInspectionMode
import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.feature.reader.R
import com.avstaim.listai.reader.pagination.IncrementalPaginationEngine
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.PaginationSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking

/** Semantic anchor outlives layout sessions. Only a completed user turn changes it. */
internal class ReaderPosition(location: BookLocation? = null, cover: String = "open") {
    var location: BookLocation? by mutableStateOf(location)
        private set
    var cover: String by mutableStateOf(cover)
        private set

    fun initialState(snapshot: PaginationSnapshot): PhysicalBookState = when (cover) {
        "front" -> PhysicalBookState.ClosedFront
        "back" -> PhysicalBookState.ClosedBack
        "boundary" -> PhysicalBookState.BackBoundary
        else -> PhysicalBookState.Open(snapshot.anchorPageIndex)
    }

    fun commit(state: PhysicalBookState, snapshot: PaginationSnapshot) {
        when (state) {
            PhysicalBookState.ClosedFront -> { cover = "front"; location = snapshot.pages.first().start }
            PhysicalBookState.ClosedBack -> { cover = "back"; location = snapshot.pages.last().endExclusive }
            PhysicalBookState.BackBoundary -> { cover = "boundary"; location = snapshot.pages.last().endExclusive }
            is PhysicalBookState.Open -> { cover = "open"; location = snapshot.pages[state.semanticPageIndex].start }
        }
    }

    companion object {
        val Saver = listSaver<ReaderPosition, Any>(
            save = { listOf(it.location?.blockId?.value.orEmpty(), it.location?.charOffset ?: 0,
                it.location?.fallbackGlobalOffset ?: 0L, it.cover) },
            restore = { values -> ReaderPosition((values[0] as String).takeIf(String::isNotEmpty)?.let {
                BookLocation(BlockId(it), values[1] as Int, values[2] as Long)
            }, values[3] as String) },
        )
    }
}

@Composable
internal fun rememberReaderPosition(document: BookDocument, initialLocation: BookLocation? = null): ReaderPosition =
    rememberSaveable(document.id.value, saver = ReaderPosition.Saver) { ReaderPosition(initialLocation) }

internal data class PreparedPagination(val snapshot: PaginationSnapshot, val surfaces: List<DemoPageSurface>)
internal class PaginationSession(val key: LayoutKey) {
    var ready: PreparedPagination? by mutableStateOf(null)
    var failed: Boolean by mutableStateOf(false)
}

@Composable
internal fun rememberPaginationSession(document: BookDocument, key: LayoutKey, position: ReaderPosition): PaginationSession {
    val session = remember(document, key) { PaginationSession(key) }
    val missingImage = stringResource(R.string.reader_missing_image)
    if (LocalInspectionMode.current) {
        // Preview tooling does not run the asynchronous loading lifecycle. Production stays off-main.
        val preview = remember(session) { runBlocking {
            val snapshot = IncrementalPaginationEngine(AndroidBookMeasurer(document, missingImage))
                .paginate(document, position.location, key).last()
            PreparedPagination(snapshot, snapshot.pages.map { PaginatedPageSurface(it, key).asBookSurface() })
        } }
        session.ready = preview
        return session
    }
    // Anchor deliberately is not an effect key: turning a leaf must not restart pagination.
    LaunchedEffect(session) {
        val anchor = position.location
        try {
            val surfaces = mutableListOf<DemoPageSurface>()
            IncrementalPaginationEngine(AndroidBookMeasurer(document, missingImage))
                .paginate(document, anchor, key)
                .map { snapshot ->
                    for (index in surfaces.size until snapshot.pages.size) {
                        currentCoroutineContext().ensureActive()
                        surfaces += PaginatedPageSurface(snapshot.pages[index], key).asBookSurface()
                    }
                    PreparedPagination(snapshot, surfaces.toList())
                }
                .flowOn(Dispatchers.Default)
                .collect {
                    currentCoroutineContext().ensureActive()
                    // Each effect owns its session object: obsolete work cannot overwrite a new key.
                    session.ready = it
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            session.failed = true
        }
    }
    return session
}

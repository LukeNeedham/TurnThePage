package com.avstaim.listai.feature.reader.bookview

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalContext
import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.feature.reader.R
import com.avstaim.listai.reader.pagination.IncrementalPaginationEngine
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.PaginationSnapshot
import com.avstaim.listai.reader.pagination.PageMode
import com.avstaim.listai.reader.rendering.cache.SurfaceStore
import com.avstaim.listai.reader.rendering.cache.SurfaceStoreState
import com.avstaim.listai.reader.rendering.turn.TurnDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch

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

internal class PreparedPagination(
    val snapshot: PaginationSnapshot,
    val surfaces: List<DemoPageSurface?>,
    val cacheBytes: Long,
    val cachedPages: Int,
    val renderedPages: Long,
    val interactionToken: Any = Any(),
)

private val SurfaceRenderDispatcher = Dispatchers.Default.limitedParallelism(1)

internal class PaginationSession(
    val key: LayoutKey,
    document: BookDocument,
    missingImage: String,
    private val scope: CoroutineScope,
) {
    var ready: PreparedPagination? by mutableStateOf(null); private set
    var failed: Boolean by mutableStateOf(false)
    private var index: PaginationSnapshot? = null
    private var position: PhysicalBookState? = null
    private var presentation = if (key.pageMode == PageMode.SPREAD) BookPresentation.SPREAD else BookPresentation.SINGLE
    private var direction = TurnDirection.FORWARD
    private var trimmed = false
    private val rasterizer = AndroidPageRasterizer(document, key, missingImage)
    val store = SurfaceStore<Int, PaginatedPageSurface>(
        scope, SurfaceRenderDispatcher, PAGE_CACHE_BYTES, { it.byteCount },
    ) { pageIndex -> rasterizer.render(requireNotNull(index).pages[pageIndex]) }
    private var cached = SurfaceStoreState<Int, PaginatedPageSurface>()

    fun updateIndex(snapshot: PaginationSnapshot, readingPosition: ReaderPosition, requestSurfaces: Boolean = true) {
        index = snapshot
        if (position == null) position = readingPosition.initialState(snapshot)
        if (requestSurfaces) request(requireNotNull(position), presentation, direction)
        publish()
    }

    fun updateCache(state: SurfaceStoreState<Int, PaginatedPageSurface>) {
        cached = state
        failed = state.failedKeys.isNotEmpty()
        publish()
    }

    fun request(state: PhysicalBookState, mode: BookPresentation, turnDirection: TurnDirection = direction) {
        if (position != state) trimmed = false
        position = state
        presentation = mode
        direction = turnDirection
        val snapshot = index ?: return
        val plan = bookSurfacePlan(state, mode, snapshot.pages.size, direction)
        store.request(plan.required, if (trimmed) emptyList() else plan.prefetch)
    }

    fun trimMemory() {
        trimmed = true
        store.trimMemory()
        scope.launch(SurfaceRenderDispatcher) { rasterizer.clearMeasurements() }
    }

    fun close() { store.close(); ready = null }

    private fun publish() {
        val snapshot = index ?: return
        val state = position ?: return
        val plan = bookSurfacePlan(state, presentation, snapshot.pages.size, direction)
        if (plan.required.any { it !in cached.values }) return
        val wanted = (plan.required + if (trimmed) emptyList() else plan.prefetch).toSet()
        val window = cached.values.filterKeys { it in wanted }.mapValues { (pageIndex, surface) ->
            surface.asBookSurface(snapshot.pages[pageIndex].displayNumber)
        }
        ready = PreparedPagination(snapshot, BookSurfaceWindow(snapshot.pages.size, window),
            cached.bytes, cached.values.size, cached.rendered)
    }
}

@Composable
internal fun rememberPaginationSession(document: BookDocument, key: LayoutKey, position: ReaderPosition): PaginationSession {
    val missingImage = stringResource(R.string.reader_missing_image)
    val scope = rememberCoroutineScope()
    val inspection = LocalInspectionMode.current
    val session = remember(document, key, missingImage, inspection) {
        PaginationSession(key, document, missingImage, scope).also { created ->
            if (inspection) runBlocking {
                // Tooling has no loading lifecycle. Initialize a new, not-yet-published session.
                val snapshot = IncrementalPaginationEngine(AndroidBookMeasurer(document, missingImage))
                    .paginate(document, position.location, key).last()
                val rasterizer = AndroidPageRasterizer(document, key, missingImage)
                val mode = if (key.pageMode == PageMode.SPREAD) BookPresentation.SPREAD else BookPresentation.SINGLE
                val plan = bookSurfacePlan(position.initialState(snapshot), mode, snapshot.pages.size)
                val values = plan.required.associateWith { rasterizer.render(snapshot.pages[it]) }
                created.updateCache(SurfaceStoreState(values, values.values.sumOf { it.byteCount }, values.size.toLong()))
                created.updateIndex(snapshot, position, requestSurfaces = false)
                created.store.close()
            }
        }
    }
    val context = LocalContext.current.applicationContext
    DisposableEffect(session) {
        val callbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() = session.trimMemory()
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) session.trimMemory()
            }
        }
        if (!inspection) context.registerComponentCallbacks(callbacks)
        onDispose {
            if (!inspection) context.unregisterComponentCallbacks(callbacks)
            session.close()
        }
    }
    if (inspection) return session
    LaunchedEffect(session) { session.store.state.collect { session.updateCache(it) } }
    LaunchedEffect(session) {
        try {
            IncrementalPaginationEngine(AndroidBookMeasurer(document, missingImage))
                .paginate(document, position.location, key)
                .flowOn(Dispatchers.Default)
                .collect { session.updateIndex(it, position) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            session.failed = true
        }
    }
    return session
}

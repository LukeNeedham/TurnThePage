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
import com.avstaim.listai.core.model.CoverPosition
import com.avstaim.listai.core.model.ReadingProgress
import com.avstaim.listai.core.model.readingBlocks
import com.avstaim.listai.feature.reader.R
import com.avstaim.listai.reader.pagination.IncrementalPaginationEngine
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.PaginationSnapshot
import com.avstaim.listai.reader.pagination.PageMode
import com.avstaim.listai.reader.pagination.pageIndexContaining
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
internal class ReaderPosition(location: BookLocation? = null, cover: String = "open",
    tocEntryIndex: Int? = null, tocCharOffset: Int = 0) {
    var location: BookLocation? by mutableStateOf(location)
        private set
    var cover: String by mutableStateOf(cover)
        private set
    var tocEntryIndex: Int? by mutableStateOf(tocEntryIndex); private set
    var tocCharOffset: Int by mutableStateOf(tocCharOffset); private set

    fun initialState(snapshot: PaginationSnapshot, contents: PhysicalContents? = null,
        tocSnapshot: PaginationSnapshot? = null): PhysicalBookState = when (cover) {
        "front" -> PhysicalBookState.ClosedFront
        "back" -> PhysicalBookState.ClosedBack
        "boundary" -> PhysicalBookState.BackBoundary
        else -> PhysicalBookState.Open(if (tocEntryIndex != null && contents != null && tocSnapshot != null)
            tocSnapshot.pages.pageIndexContaining(contents.location(requireNotNull(tocEntryIndex), tocCharOffset))
            else (tocSnapshot?.pages?.size ?: 0) + snapshot.pages.pageIndexContaining(location ?: snapshot.pages.first().start))
    }

    fun commit(state: PhysicalBookState, snapshot: PaginationSnapshot, contents: PhysicalContents? = null,
        tocSnapshot: PaginationSnapshot? = null) {
        val tocCount = tocSnapshot?.pages?.size ?: 0
        if (state is PhysicalBookState.Open && state.semanticPageIndex < tocCount && contents != null) {
            val anchor = requireNotNull(tocSnapshot).pages[state.semanticPageIndex].start
            cover = "open"
            tocEntryIndex = contents.entryIndex(anchor)
            tocCharOffset = anchor.charOffset
            return
        }
        tocEntryIndex = null
        tocCharOffset = 0
        when (state) {
            PhysicalBookState.ClosedFront -> { cover = "front"; location = snapshot.pages.first().start }
            PhysicalBookState.ClosedBack -> { cover = "back"; location = snapshot.pages.last().endExclusive }
            PhysicalBookState.BackBoundary -> { cover = "boundary"; location = snapshot.pages.last().endExclusive }
            is PhysicalBookState.Open -> { cover = "open"; location = snapshot.pages[state.semanticPageIndex - tocCount].start }
        }
    }

    fun progress(totalCharacters: Long): ReadingProgress {
        val pose = when (cover) {
            "front" -> CoverPosition.FRONT
            "back" -> CoverPosition.BACK
            "boundary" -> CoverPosition.BOUNDARY
            else -> CoverPosition.OPEN
        }
        val fraction = when (pose) {
            CoverPosition.FRONT -> 0f
            CoverPosition.BACK, CoverPosition.BOUNDARY -> 1f
            CoverPosition.OPEN -> if (location != null && location!!.fallbackGlobalOffset >= totalCharacters - 1) 1f else
                ((location?.fallbackGlobalOffset ?: 0).toDouble() /
                totalCharacters.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
        }
        return ReadingProgress(location, pose, fraction, tocEntryIndex, tocCharOffset)
    }

    companion object {
        val Saver = listSaver<ReaderPosition, Any>(
            save = { listOf(it.location?.blockId?.value.orEmpty(), it.location?.charOffset ?: 0,
                it.location?.fallbackGlobalOffset ?: 0L, it.cover, it.tocEntryIndex ?: -1, it.tocCharOffset) },
            restore = { values -> ReaderPosition((values[0] as String).takeIf(String::isNotEmpty)?.let {
                BookLocation(BlockId(it), values[1] as Int, values[2] as Long)
            }, values[3] as String, (values.getOrNull(4) as? Int)?.takeIf { it >= 0 },
                values.getOrNull(5) as? Int ?: 0) },
        )
    }
}

@Composable
internal fun rememberReaderPosition(document: BookDocument, initialLocation: BookLocation? = null,
    initialCover: CoverPosition = CoverPosition.OPEN, initialTocEntryIndex: Int? = null,
    initialTocCharOffset: Int = 0): ReaderPosition =
    rememberSaveable(document.id.value, saver = ReaderPosition.Saver) {
        ReaderPosition(initialLocation ?: document.readingBlocks().firstOrNull()?.let { BookLocation(it.id, 0, 0) },
            initialCover.name.lowercase(), initialTocEntryIndex, initialTocCharOffset)
    }

internal class PreparedPagination(
    val snapshot: PaginationSnapshot,
    val surfaces: List<DemoPageSurface?>,
    val cacheBytes: Long,
    val cachedPages: Int,
    val renderedPages: Long,
    val contents: PhysicalContents? = null,
    val tocSnapshot: PaginationSnapshot? = null,
    val interactionToken: Any = Any(),
)

private val SurfaceRenderDispatcher = Dispatchers.Default.limitedParallelism(1)

internal class PaginationSession(
    val key: LayoutKey,
    document: BookDocument,
    missingImage: String,
    private val scope: CoroutineScope,
    val contents: PhysicalContents? = null,
) {
    var ready: PreparedPagination? by mutableStateOf(null); private set
    var failed: Boolean by mutableStateOf(false)
    private var index: PaginationSnapshot? = null
    private var tocIndex: PaginationSnapshot? = null
    private var position: PhysicalBookState? = null
    private var presentation = if (key.pageMode == PageMode.SPREAD) BookPresentation.SPREAD else BookPresentation.SINGLE
    private var direction = TurnDirection.FORWARD
    private var trimmed = false
    private val rasterizer = AndroidPageRasterizer(document, key, missingImage,
        if (contents == null) MEASUREMENT_CACHE_BYTES else MEASUREMENT_CACHE_BYTES * 3 / 4)
    private val tocRasterizer = contents?.let {
        AndroidPageRasterizer(it.document, key, missingImage, MEASUREMENT_CACHE_BYTES / 4)
    }
    val store = SurfaceStore<Int, PaginatedPageSurface>(
        scope, SurfaceRenderDispatcher, PAGE_CACHE_BYTES, { it.byteCount },
    ) { pageIndex ->
        val tocCount = tocIndex?.pages?.size ?: 0
        if (pageIndex < tocCount) requireNotNull(tocRasterizer).render(requireNotNull(tocIndex).pages[pageIndex])
        else rasterizer.render(requireNotNull(index).pages[pageIndex - tocCount])
    }
    private var cached = SurfaceStoreState<Int, PaginatedPageSurface>()

    fun updateIndex(snapshot: PaginationSnapshot, readingPosition: ReaderPosition, requestSurfaces: Boolean = true,
        tocSnapshot: PaginationSnapshot? = tocIndex) {
        index = snapshot
        tocIndex = tocSnapshot
        if (position == null) position = readingPosition.initialState(snapshot, contents, tocIndex)
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
        val plan = bookSurfacePlan(state, mode, snapshot.pages.size + (tocIndex?.pages?.size ?: 0), direction)
        store.request(plan.required, if (trimmed) emptyList() else plan.prefetch)
    }

    fun trimMemory() {
        trimmed = true
        store.trimMemory()
        scope.launch(SurfaceRenderDispatcher) { rasterizer.clearMeasurements(); tocRasterizer?.clearMeasurements() }
    }

    fun close() { store.close(); ready = null }

    private fun publish() {
        val snapshot = index ?: return
        val state = position ?: return
        val tocCount = tocIndex?.pages?.size ?: 0
        val totalPages = snapshot.pages.size + tocCount
        val plan = bookSurfacePlan(state, presentation, totalPages, direction)
        if (plan.required.any { it !in cached.values }) return
        val wanted = (plan.required + if (trimmed) emptyList() else plan.prefetch).toSet()
        val window = cached.values.filterKeys { it in wanted }.mapValues { (pageIndex, surface) ->
            surface.asBookSurface(if (pageIndex < tocCount) pageIndex + 1 else snapshot.pages[pageIndex - tocCount].displayNumber)
        }
        ready = PreparedPagination(snapshot, BookSurfaceWindow(totalPages, window),
            cached.bytes, cached.values.size, cached.rendered, contents, tocIndex)
    }
}

@Composable
internal fun rememberPaginationSession(document: BookDocument, key: LayoutKey, position: ReaderPosition,
    includeContents: Boolean = true): PaginationSession {
    val missingImage = stringResource(R.string.reader_missing_image)
    val contentsTitle = stringResource(R.string.reader_contents)
    val beginText = stringResource(R.string.reader_text_start)
    val contents = remember(document, contentsTitle, beginText, includeContents) {
        if (includeContents) PhysicalContents(document, contentsTitle, beginText) else null
    }
    val scope = rememberCoroutineScope()
    val inspection = LocalInspectionMode.current
    val session = remember(document, key, missingImage, inspection, contents) {
        PaginationSession(key, document, missingImage, scope, contents).also { created ->
            if (inspection) runBlocking {
                // Tooling has no loading lifecycle. Initialize a new, not-yet-published session.
                val snapshot = IncrementalPaginationEngine(AndroidBookMeasurer(document, missingImage))
                    .paginate(document, position.location, key).last()
                val rasterizer = AndroidPageRasterizer(document, key, missingImage)
                val tocSnapshot = contents?.let {
                    IncrementalPaginationEngine(AndroidBookMeasurer(it.document, missingImage))
                        .paginate(it.document, null, key).last()
                }
                val tocRasterizer = contents?.let { AndroidPageRasterizer(it.document, key, missingImage) }
                val tocCount = tocSnapshot?.pages?.size ?: 0
                val mode = if (key.pageMode == PageMode.SPREAD) BookPresentation.SPREAD else BookPresentation.SINGLE
                val plan = bookSurfacePlan(position.initialState(snapshot, contents, tocSnapshot), mode, snapshot.pages.size + tocCount)
                val values = plan.required.associateWith { if (it < tocCount)
                    requireNotNull(tocRasterizer).render(requireNotNull(tocSnapshot).pages[it])
                    else rasterizer.render(snapshot.pages[it - tocCount]) }
                created.updateCache(SurfaceStoreState(values, values.values.sumOf { it.byteCount }, values.size.toLong()))
                created.updateIndex(snapshot, position, requestSurfaces = false, tocSnapshot = tocSnapshot)
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
            val tocSnapshot = contents?.let {
                kotlinx.coroutines.withContext(Dispatchers.Default) {
                    IncrementalPaginationEngine(AndroidBookMeasurer(it.document, missingImage))
                        .paginate(it.document, null, key).last()
                }
            }
            IncrementalPaginationEngine(AndroidBookMeasurer(document, missingImage))
                .paginate(document, position.location, key)
                .flowOn(Dispatchers.Default)
                .collect { session.updateIndex(it, position, tocSnapshot = tocSnapshot) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            session.failed = true
        }
    }
    return session
}

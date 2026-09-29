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
import com.avstaim.listai.core.designsystem.PlyosTypefaceFamily
import com.avstaim.listai.core.designsystem.rememberPlyosTypefaceFamily
import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookDocument
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.CoverPosition
import com.avstaim.listai.core.model.ReadingProgress
import com.avstaim.listai.core.model.NoteId
import com.avstaim.listai.core.model.readingBlocks
import com.avstaim.listai.core.model.readingLength
import com.avstaim.listai.feature.reader.R
import com.avstaim.listai.reader.pagination.IncrementalPaginationEngine
import com.avstaim.listai.reader.pagination.LayoutKey
import com.avstaim.listai.reader.pagination.PaginationSnapshot
import com.avstaim.listai.reader.pagination.PageMode
import com.avstaim.listai.reader.pagination.pageIndexContaining
import com.avstaim.listai.reader.pagination.readingFlow
import com.avstaim.listai.reader.pagination.resolveLocation
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Semantic anchor outlives layout sessions. Only a completed user turn changes it. */
internal class ReaderPosition(location: BookLocation? = null, cover: String = "open",
    tocEntryIndex: Int? = null, tocCharOffset: Int = 0,
    noteLocation: BookLocation? = null, noteId: NoteId? = null, visit: FootnoteVisit? = null,
    annotationLocation: BookLocation? = null, titleLocation: BookLocation? = null,
    contentsNoteId: NoteId? = null, contentsNoteLocation: BookLocation? = null,
    contentsNoteOrigin: BookLocation? = null) {
    var location: BookLocation? by mutableStateOf(location)
        private set
    var cover: String by mutableStateOf(cover)
        private set
    var tocEntryIndex: Int? by mutableStateOf(tocEntryIndex); private set
    var tocCharOffset: Int by mutableStateOf(tocCharOffset); private set
    var noteLocation: BookLocation? by mutableStateOf(noteLocation); private set
    var noteId: NoteId? by mutableStateOf(noteId); private set
    var visit: FootnoteVisit? by mutableStateOf(visit); private set
    var annotationLocation: BookLocation? by mutableStateOf(annotationLocation); private set
    var titleLocation: BookLocation? by mutableStateOf(titleLocation); private set
    private var contentsNoteId = contentsNoteId
    private var contentsNoteLocation = contentsNoteLocation
    private var contentsNoteOrigin = contentsNoteOrigin

    /** Pre-1.2 checkpoints can refer to a native title while still using the main anchor field. */
    fun restoreNativeTitle(nativeTitleIds: Set<BlockId>) {
        if (titleLocation == null && annotationLocation == null && tocEntryIndex == null && noteId == null) {
            titleLocation = location?.takeIf { it.blockId in nativeTitleIds }
        }
    }

    fun prepareJump(request: ReaderJumpRequest): ReaderJumpRequest {
        if (request.progress.tocEntryIndex != null && tocEntryIndex == null) {
            contentsNoteId = noteId
            contentsNoteLocation = noteLocation
            contentsNoteOrigin = visit?.origin
        }
        return if (request.returningFromContents && contentsNoteId != null)
            request.copy(noteId = contentsNoteId, noteTarget = contentsNoteLocation, origin = contentsNoteOrigin)
        else request
    }

    fun initialState(snapshot: PaginationSnapshot, contents: PhysicalContents? = null,
        tocSnapshot: PaginationSnapshot? = null, notes: PhysicalNotes? = null,
        notesSnapshot: PaginationSnapshot? = null, annotationSnapshot: PaginationSnapshot? = null): PhysicalBookState {
        val annotationCount = annotationSnapshot.annotationPaperCount
        val frontmatterLocation = titleLocation ?: annotationLocation
        return when (cover) {
            "front" -> PhysicalBookState.ClosedFront
            "back" -> PhysicalBookState.ClosedBack
            "boundary" -> PhysicalBookState.BackBoundary
            "front_boundary" -> if (annotationSnapshot == null && snapshot.key.pageMode == PageMode.SINGLE)
                PhysicalBookState.FrontBoundary else PhysicalBookState.Open(
                    frontmatterLocation?.let { annotationSnapshot?.pages?.pageIndexContaining(it) } ?: 0)
            else -> if (frontmatterLocation != null && annotationSnapshot != null) {
                PhysicalBookState.Open(annotationSnapshot.pages.pageIndexContaining(frontmatterLocation))
            } else PhysicalBookState.Open(if (noteId != null && notes != null && notesSnapshot != null)
                annotationCount + (tocSnapshot?.pages?.size ?: 0) + snapshot.pages.size +
                    notesSnapshot.pages.pageIndexContaining(noteLocation ?: notes.targets.getValue(requireNotNull(noteId)))
                else if (tocEntryIndex != null && contents != null && tocSnapshot != null)
                annotationCount + tocSnapshot.pages.pageIndexContaining(contents.location(requireNotNull(tocEntryIndex), tocCharOffset))
                else if (snapshot.pages.isEmpty()) 0
                else annotationCount + (tocSnapshot?.pages?.size ?: 0) +
                    snapshot.pages.pageIndexContaining(location ?: snapshot.pages.first().start))
        }
    }

    fun commit(state: PhysicalBookState, snapshot: PaginationSnapshot, contents: PhysicalContents? = null,
        tocSnapshot: PaginationSnapshot? = null, notes: PhysicalNotes? = null,
        notesSnapshot: PaginationSnapshot? = null, annotationSnapshot: PaginationSnapshot? = null,
        titleBlockIds: Set<BlockId> = emptySet()) {
        val annotationCount = annotationSnapshot.annotationPaperCount
        val tocCount = tocSnapshot?.pages?.size ?: 0
        val notesStart = annotationCount + tocCount + snapshot.pages.size
        if (tocEntryIndex != null && (state !is PhysicalBookState.Open ||
                state.semanticPageIndex !in annotationCount until annotationCount + tocCount)) {
            clearContentsOrigin()
        }
        if (state == PhysicalBookState.FrontBoundary) {
            val anchor = annotationSnapshot?.pages?.firstOrNull()?.start
            annotationLocation = anchor?.takeUnless { it.blockId in titleBlockIds }
            titleLocation = anchor?.takeIf { it.blockId in titleBlockIds }
            clearNotes()
            tocEntryIndex = null
            tocCharOffset = 0
            cover = "front_boundary"
            return
        }
        if (state is PhysicalBookState.Open && state.semanticPageIndex < annotationCount) {
            val annotationPages = requireNotNull(annotationSnapshot).pages
            val anchor = annotationPages[state.semanticPageIndex].start
            titleLocation = anchor.takeIf { it.blockId in titleBlockIds }
            annotationLocation = anchor.takeUnless { it.blockId in titleBlockIds }
            clearNotes()
            tocEntryIndex = null
            tocCharOffset = 0
            cover = "open"
            return
        }
        annotationLocation = null
        titleLocation = null
        if (state is PhysicalBookState.Open && state.semanticPageIndex >= notesStart && notes != null && notesSnapshot != null) {
            noteLocation = notesSnapshot.pages[state.semanticPageIndex - notesStart].start
            noteId = notes.noteAt(requireNotNull(noteLocation)) ?: notes.targets.keys.firstOrNull()
            cover = "open"
            tocEntryIndex = null
            tocCharOffset = 0
            return
        }
        clearNotes()
        if (state is PhysicalBookState.Open && state.semanticPageIndex < annotationCount + tocCount && contents != null) {
            val anchor = requireNotNull(tocSnapshot).pages[state.semanticPageIndex - annotationCount].start
            cover = "open"
            tocEntryIndex = contents.entryIndex(anchor)
            tocCharOffset = anchor.charOffset
            return
        }
        tocEntryIndex = null
        tocCharOffset = 0
        when (state) {
            PhysicalBookState.ClosedFront -> { cover = "front" }
            PhysicalBookState.FrontBoundary -> Unit
            PhysicalBookState.ClosedBack -> { cover = "back"; location = snapshot.pages.lastOrNull()?.endExclusive ?: location }
            PhysicalBookState.BackBoundary -> { cover = "boundary"; location = snapshot.pages.lastOrNull()?.endExclusive ?: location }
            is PhysicalBookState.Open -> { cover = "open"; location = snapshot.pages[state.semanticPageIndex - annotationCount - tocCount].start }
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
            CoverPosition.FRONT -> ((location?.fallbackGlobalOffset ?: 0).toDouble() / totalCharacters.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
            CoverPosition.BACK, CoverPosition.BOUNDARY -> 1f
            CoverPosition.OPEN -> if (location != null && location!!.fallbackGlobalOffset >= totalCharacters - 1) 1f else
                ((location?.fallbackGlobalOffset ?: 0).toDouble() /
                totalCharacters.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
        }
        return ReadingProgress(location, pose, fraction, tocEntryIndex, tocCharOffset, annotationLocation, titleLocation)
    }

    /** Explicit navigation preserves the selected character, including a chapter mid-page. */
    fun commitJump(progress: ReadingProgress) {
        clearNotes()
        location = progress.location
        cover = progress.cover.name.lowercase()
        tocEntryIndex = progress.tocEntryIndex
        tocCharOffset = progress.tocCharOffset
        annotationLocation = progress.annotationLocation
        titleLocation = progress.titleLocation
    }

    fun commitJump(request: ReaderJumpRequest, notes: PhysicalNotes?) {
        val target = request.noteTarget ?: notes?.targets?.get(request.noteId)
        if (request.noteId != null && target != null) {
            annotationLocation = null
            titleLocation = null
            noteId = request.noteId
            noteLocation = target
            visit = request.origin?.let { FootnoteVisit(request.noteId, it, target) }
            cover = "open"
            tocEntryIndex = null
            tocCharOffset = 0
        } else commitJump(request.progress)
        if (request.returningFromContents || request.progress.tocEntryIndex == null) {
            clearContentsOrigin()
        }
    }

    private fun clearContentsOrigin() {
        contentsNoteId = null; contentsNoteLocation = null; contentsNoteOrigin = null
    }

    fun clearNotes() { noteLocation = null; noteId = null; visit = null }

    companion object {
        val Saver = listSaver<ReaderPosition, Any>(
            save = { listOf(it.location?.blockId?.value.orEmpty(), it.location?.charOffset ?: 0,
                it.location?.fallbackGlobalOffset ?: 0L, it.cover, it.tocEntryIndex ?: -1, it.tocCharOffset,
                it.noteLocation?.blockId?.value.orEmpty(), it.noteLocation?.charOffset ?: 0,
                it.noteLocation?.fallbackGlobalOffset ?: 0L, it.noteId?.value.orEmpty(),
                it.visit?.noteId?.value.orEmpty(), it.visit?.origin?.blockId?.value.orEmpty(),
                it.visit?.origin?.charOffset ?: 0, it.visit?.origin?.fallbackGlobalOffset ?: 0L,
                it.visit?.target?.blockId?.value.orEmpty(), it.visit?.target?.charOffset ?: 0,
                it.visit?.target?.fallbackGlobalOffset ?: 0L,
                it.annotationLocation?.blockId?.value.orEmpty(), it.annotationLocation?.charOffset ?: 0,
                it.annotationLocation?.fallbackGlobalOffset ?: 0L,
                it.titleLocation?.blockId?.value.orEmpty(), it.titleLocation?.charOffset ?: 0,
                it.titleLocation?.fallbackGlobalOffset ?: 0L,
                it.contentsNoteId?.value.orEmpty(),
                it.contentsNoteLocation?.blockId?.value.orEmpty(), it.contentsNoteLocation?.charOffset ?: 0,
                it.contentsNoteLocation?.fallbackGlobalOffset ?: 0L,
                it.contentsNoteOrigin?.blockId?.value.orEmpty(), it.contentsNoteOrigin?.charOffset ?: 0,
                it.contentsNoteOrigin?.fallbackGlobalOffset ?: 0L) },
            restore = { values -> ReaderPosition((values[0] as String).takeIf(String::isNotEmpty)?.let {
                BookLocation(BlockId(it), values[1] as Int, values[2] as Long)
            }, values[3] as String, (values.getOrNull(4) as? Int)?.takeIf { it >= 0 },
                values.getOrNull(5) as? Int ?: 0,
                (values.getOrNull(6) as? String)?.takeIf(String::isNotEmpty)?.let {
                    BookLocation(BlockId(it), values[7] as Int, values[8] as Long)
                }, (values.getOrNull(9) as? String)?.takeIf(String::isNotEmpty)?.let(::NoteId),
                (values.getOrNull(10) as? String)?.takeIf(String::isNotEmpty)?.let {
                    FootnoteVisit(NoteId(it), BookLocation(BlockId(values[11] as String), values[12] as Int, values[13] as Long),
                        BookLocation(BlockId(values[14] as String), values[15] as Int, values[16] as Long))
                }, (values.getOrNull(17) as? String)?.takeIf(String::isNotEmpty)?.let {
                    BookLocation(BlockId(it), values[18] as Int, values[19] as Long)
                }, (values.getOrNull(20) as? String)?.takeIf(String::isNotEmpty)?.let {
                    BookLocation(BlockId(it), values[21] as Int, values[22] as Long)
                }, (values.getOrNull(23) as? String)?.takeIf(String::isNotEmpty)?.let(::NoteId),
                (values.getOrNull(24) as? String)?.takeIf(String::isNotEmpty)?.let {
                    BookLocation(BlockId(it), values[25] as Int, values[26] as Long)
                }, (values.getOrNull(27) as? String)?.takeIf(String::isNotEmpty)?.let {
                    BookLocation(BlockId(it), values[28] as Int, values[29] as Long)
                }) },
        )
    }
}

@Composable
internal fun rememberReaderPosition(document: BookDocument, initialLocation: BookLocation? = null,
    initialCover: CoverPosition = CoverPosition.OPEN, initialTocEntryIndex: Int? = null,
    initialTocCharOffset: Int = 0, initialNoteId: NoteId? = null, readerSession: Long = 0L,
    initialAnnotationLocation: BookLocation? = null, initialTitleLocation: BookLocation? = null): ReaderPosition {
    // Session is an input: a live bookmark selection resets state, while a restored process
    // can consume its saved Bundle even though the controller's counter restarted.
    val position = rememberSaveable(document.id.value, readerSession, saver = ReaderPosition.Saver) {
        ReaderPosition(initialLocation ?: document.readingBlocks().firstOrNull()?.let { BookLocation(it.id, 0, 0) },
            initialCover.name.lowercase(), initialTocEntryIndex, initialTocCharOffset, noteId = initialNoteId,
            annotationLocation = initialAnnotationLocation, titleLocation = initialTitleLocation)
    }
    // Publish a migrated checkpoint before the first progress effect, including restored Bundles.
    return remember(document, position) { position.apply { restoreNativeTitle(document.titlePageBlockIds.toSet()) } }
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
    val jump: PreparedReaderJump? = null,
    val notes: PhysicalNotes? = null,
    val notesSnapshot: PaginationSnapshot? = null,
    val annotationSnapshot: PaginationSnapshot? = null,
    val insideFront: DemoPageSurface? = null,
    val titleBlockIds: Set<BlockId> = emptySet(),
) {
    val annotationPageCount: Int get() = annotationSnapshot.annotationPaperCount
    val sourcePageOffset: Int get() = annotationPageCount + (tocSnapshot?.pages?.size ?: 0)
}

private val SurfaceRenderDispatcher = Dispatchers.Default.limitedParallelism(1)

internal data class ReaderSurfaceKey(val pageIndex: Int, val contentsNumbers: ContentsPageNumbers? = null)

internal class PaginationSession(
    val key: LayoutKey,
    document: BookDocument,
    missingImage: String,
    private val scope: CoroutineScope,
    val contents: PhysicalContents? = null,
    private val contentsLabel: String = "Contents",
    val notes: PhysicalNotes? = null,
    val annotation: PhysicalAnnotation? = null,
    private val typefaces: PlyosTypefaceFamily = PlyosTypefaceFamily.systemSerif,
) {
    var ready: PreparedPagination? by mutableStateOf(null); private set
    var failed: Boolean by mutableStateOf(false)
    var failedJumpSequence: Long? by mutableStateOf(null); private set
    private var closed = false
    private var index: PaginationSnapshot? = null
    private var tocIndex: PaginationSnapshot? = null
    private var notesIndex: PaginationSnapshot? = null
    private var annotationIndex: PaginationSnapshot? = null
    private var readingPosition: ReaderPosition? = null
    private var contentsNumbers: Map<BlockId, Int?> = emptyMap()
    private var position: PhysicalBookState? = null
    private var presentation = if (key.pageMode == PageMode.SPREAD) BookPresentation.SPREAD else BookPresentation.SINGLE
    private var direction = TurnDirection.FORWARD
    private var trimmed = false
    private data class JumpPreparation(val request: ReaderJumpRequest,
        val source: PreparedPagination, val sourceKeys: Map<Int, ReaderSurfaceKey>)
    private var jump: JumpPreparation? = null
    // Anchor normalization uses original source coordinates, including projected title blocks.
    private val sourceFlow = document.readingFlow(includeNativeTitle = true)
    private val nativeTitleIds = document.titlePageBlockIds.toSet()
    private val sourceLength = document.readingLength()
    private val rasterizer = AndroidPageRasterizer(document, key, missingImage,
        MEASUREMENT_CACHE_BYTES, typefaces)
    private val tocRasterizer = contents?.let { AndroidContentsRasterizer(it, key, typefaces) }
    private val annotationRasterizer = annotation?.let {
        AndroidPageRasterizer(it.document,
            key.forAnnotation(),
            missingImage, MEASUREMENT_CACHE_BYTES, typefaces)
    }
    private val notesRasterizer = notes?.let {
        AndroidPageRasterizer(it.document, key.forNotes(), missingImage, MEASUREMENT_CACHE_BYTES,
            typefaces)
    }
    private val notesCount: Int get() = if (index?.complete == true) notesIndex?.pages?.size ?: 0 else 0
    private val annotationCount: Int get() = annotationIndex.annotationPaperCount
    private val pageCount: Int get() = annotationCount + (index?.pages?.size ?: 0) + (tocIndex?.pages?.size ?: 0) + notesCount
    val store = SurfaceStore<ReaderSurfaceKey, PaginatedPageSurface>(
        scope, SurfaceRenderDispatcher, PAGE_CACHE_BYTES, { it.byteCount },
    ) { request ->
        val pageIndex = request.pageIndex - annotationCount
        val tocCount = tocIndex?.pages?.size ?: 0
        if (request.pageIndex < annotationCount) requireNotNull(annotationRasterizer).render(requireNotNull(annotationIndex).pages[request.pageIndex])
        else if (pageIndex < tocCount) requireNotNull(tocRasterizer).render(requireNotNull(tocIndex).pages[pageIndex],
            requireNotNull(request.contentsNumbers))
        else if (pageIndex < tocCount + requireNotNull(index).pages.size)
            rasterizer.render(requireNotNull(index).pages[pageIndex - tocCount])
        else requireNotNull(notesRasterizer).render(requireNotNull(notesIndex).pages[pageIndex - tocCount - requireNotNull(index).pages.size])
    }
    private var cached = SurfaceStoreState<ReaderSurfaceKey, PaginatedPageSurface>()

    private fun surfaceKey(pageIndex: Int) = ReaderSurfaceKey(pageIndex,
        tocIndex?.pages?.getOrNull(pageIndex - annotationCount)?.let { ContentsPageNumbers.forPage(it, contentsNumbers) })

    fun updateIndex(snapshot: PaginationSnapshot, readingPosition: ReaderPosition, requestSurfaces: Boolean = true,
        tocSnapshot: PaginationSnapshot? = tocIndex, notesSnapshot: PaginationSnapshot? = notesIndex,
        annotationSnapshot: PaginationSnapshot? = annotationIndex) {
        index = snapshot
        tocIndex = tocSnapshot
        notesIndex = notesSnapshot
        annotationIndex = annotationSnapshot
        this.readingPosition = readingPosition
        contentsNumbers = contents?.sourcePageNumbers(snapshot, notesIndex).orEmpty()
        if (position == null) {
            if (readingPosition.noteId != null && !snapshot.complete) return
            readingPosition.restoreNativeTitle(nativeTitleIds)
            position = readingPosition.initialState(snapshot, contents, tocIndex, notes, notesIndex, annotationIndex)
        }
        if (requestSurfaces) requestCurrentWindow()
        publish()
    }

    fun updateCache(state: SurfaceStoreState<ReaderSurfaceKey, PaginatedPageSurface>) {
        cached = state
        failed = state.failedKeys.isNotEmpty()
        if (failed && jump != null) failedJumpSequence = jump?.request?.sequence
        publish()
    }

    fun request(state: PhysicalBookState, mode: BookPresentation, turnDirection: TurnDirection = direction) {
        val changed = position != state || presentation != mode
        if (position != state) trimmed = false
        position = state
        presentation = mode
        direction = turnDirection
        requestCurrentWindow()
        if (changed) publish()
    }

    fun requestJump(request: ReaderJumpRequest, source: PreparedPagination, state: PhysicalBookState,
        mode: BookPresentation) {
        position = state
        presentation = mode
        failedJumpSequence = null
        val visible = source.visibleOnly(state, mode)
        val numbers = source.contents?.sourcePageNumbers(source.snapshot, source.notesSnapshot).orEmpty()
        val titleTarget = request.progress.location?.takeIf { it.blockId in nativeTitleIds &&
            request.progress.titleLocation == null && request.progress.annotationLocation == null && request.progress.tocEntryIndex == null }
        val progress = if (titleTarget != null) request.progress.copy(titleLocation = titleTarget,
            location = readingPosition?.location?.let(sourceFlow::resolveLocation)) else request.progress.copy(
                location = request.progress.location?.let(sourceFlow::resolveLocation))
        jump = JumpPreparation(request.copy(progress = progress),
            visible, visiblePageIndices(state, mode, source.surfaces.size).associateWith { page ->
                ReaderSurfaceKey(page, source.tocSnapshot?.pages?.getOrNull(page - source.annotationPageCount)?.let {
                    ContentsPageNumbers.forPage(it, numbers)
                })
            })
        requestCurrentWindow()
        publish()
    }

    fun cancelJump(state: PhysicalBookState, mode: BookPresentation) {
        if (closed) return
        jump = null
        failedJumpSequence = null
        request(state, mode)
        publish()
    }

    /** Abort the transaction owned by this exact layout session, preserving its source pose. */
    fun cancelJump() {
        if (closed || jump == null) return
        jump = null
        failedJumpSequence = null
        requestCurrentWindow()
        publish()
    }

    private fun preparedJump(): PreparedReaderJump? = jump?.let {
        resolveReaderJump(it.request, index ?: return null, contents, tocIndex, presentation,
            notes, notesIndex, annotationCount, annotationIndex)
    }

    private fun requestCurrentWindow() {
        val snapshot = index ?: return
        val pending = jump
        if (pending != null) {
            val target = preparedJump()
            val destination = target?.let { visiblePageIndices(it.target, presentation,
                pageCount) }.orEmpty()
            store.request((insideFrontKeys() + pending.sourceKeys.values + destination.map(::surfaceKey)).distinct())
        } else {
            val plan = bookSurfacePlan(position ?: return, presentation,
                pageCount, direction)
            store.request(insideFrontKeys() + plan.required.map(::surfaceKey), if (trimmed) emptyList() else plan.prefetch.map(::surfaceKey))
        }
    }

    private fun insideFrontKeys(): List<ReaderSurfaceKey> = emptyList()

    fun trimMemory() {
        trimmed = true
        store.trimMemory()
        scope.launch(SurfaceRenderDispatcher) {
            rasterizer.clearMeasurements()
            notesRasterizer?.clearMeasurements()
            annotationRasterizer?.clearMeasurements()
        }
    }

    fun close() { closed = true; jump = null; store.close(); ready = null }

    private fun publish() {
        val snapshot = index ?: return
        val state = position ?: return
        val tocCount = tocIndex?.pages?.size ?: 0
        val totalPages = pageCount
        val pending = jump
        val destination = if (pending != null) preparedJump() ?: return else null
        val plan = if (destination != null) BookSurfacePlan(
            visiblePageIndices(destination.target, presentation, totalPages), emptyList())
            else bookSurfacePlan(state, presentation, totalPages, direction)
        if (plan.required.any { surfaceKey(it) !in cached.values }) return
        if (insideFrontKeys().any { it !in cached.values }) return
        val wanted = (plan.required + if (trimmed) emptyList() else plan.prefetch).toSet()
        val window = wanted.mapNotNull { pageIndex ->
            val surface = cached.values[surfaceKey(pageIndex)] ?: return@mapNotNull null
            val notesPage = notesIndex?.pages?.getOrNull(pageIndex - annotationCount - tocCount - snapshot.pages.size)
            val page = snapshot.pages.getOrNull(pageIndex - annotationCount - tocCount)
            val footer = if (notesPage != null) PageReadingFooter(notesPage.displayNumber, notesCount, null, notes?.title)
                else if (page == null) PageReadingFooter((pageIndex - annotationCount + 1).coerceAtLeast(1), tocCount.coerceAtLeast(1), null, contentsLabel)
                else sourcePageFooter(page.displayNumber, snapshot.pages.size.takeIf { snapshot.complete },
                    page.start.fallbackGlobalOffset, sourceLength)
            // Reuse source pixels when the complete page count arrives. Displayed snapshots pin
            // their immutable footer wrapper through a drag/settle, just like the page bitmap.
            val decorated = if (notesPage != null && notes != null) surface.withNoteReturns(
                notes.returns(notesPage, requireNotNull(notesIndex).key,
                    pending?.request?.origin?.let { origin -> pending.request.noteId?.let { noteId ->
                        FootnoteVisit(noteId, origin, pending.request.noteTarget ?: notes.targets.getValue(noteId))
                    } } ?: readingPosition?.visit)) else surface
            pageIndex to (if (pageIndex < annotationCount) decorated else decorated.withFooter(footer))
                .asBookSurface(page?.displayNumber ?: notesPage?.displayNumber ?: (pageIndex + 1))
        }.toMap()
        val sourceWindow = pending?.sourceKeys?.keys?.mapNotNull { page ->
            pending.source.surfaces[page]?.let { page to it }
        }?.toMap().orEmpty()
        ready = PreparedPagination(snapshot, BookSurfaceWindow(totalPages, sourceWindow + window),
            cached.bytes, cached.values.size, cached.rendered, contents, tocIndex, jump = destination,
            notes = notes, notesSnapshot = notesIndex.takeIf { snapshot.complete },
            annotationSnapshot = annotationIndex,
            insideFront = null, titleBlockIds = annotation?.titleBlockIds.orEmpty())
    }
}

@Composable
internal fun rememberPaginationSession(document: BookDocument, key: LayoutKey, position: ReaderPosition,
    includeContents: Boolean = true): PaginationSession {
    val missingImage = stringResource(R.string.reader_missing_image)
    val contentsTitle = stringResource(R.string.reader_contents)
    val beginText = stringResource(R.string.reader_text_start)
    val notesTitle = stringResource(R.string.reader_notes)
    val notePattern = stringResource(R.string.reader_note_number)
    val returnLabel = stringResource(R.string.reader_note_return)
    val typefaces = rememberPlyosTypefaceFamily()
    val notes = remember(document, notesTitle, notePattern, returnLabel) {
        PhysicalNotes.create(document, notesTitle, { notePattern.format(it) }, returnLabel)
    }
    val contents = remember(document, contentsTitle, beginText, includeContents, notes) {
        if (includeContents) PhysicalContents(document, contentsTitle, beginText, notes) else null
    }
    val descriptionLabel = stringResource(R.string.reader_description_heading)
    val annotation = remember(document, descriptionLabel) { PhysicalAnnotation.create(document, descriptionLabel) }
    val scope = rememberCoroutineScope()
    val inspection = LocalInspectionMode.current
    val session = remember(document, key, missingImage, inspection, contents, notes, annotation,
        position, typefaces) {
        PaginationSession(key, document, missingImage, scope, contents, contentsTitle, notes,
            annotation, typefaces).also { created ->
            if (inspection) runBlocking {
                // Tooling has no loading lifecycle. Initialize a new, not-yet-published session.
                val snapshot = IncrementalPaginationEngine(AndroidBookMeasurer(document, missingImage,
                    typefaces = typefaces))
                    .paginate(document, position.location, key).last()
                val rasterizer = AndroidPageRasterizer(document, key, missingImage,
                    typefaces = typefaces)
                val annotationSnapshot = annotation?.let {
                    IncrementalPaginationEngine(AndroidBookMeasurer(it.document, missingImage,
                        typefaces = typefaces))
                        .paginate(it.document, position.titleLocation ?: position.annotationLocation, key.forAnnotation()).last()
                }
                val annotationRasterizer = annotation?.let {
                    AndroidPageRasterizer(it.document,
                        key.forAnnotation(),
                        missingImage, typefaces = typefaces)
                }
                val annotationCount = annotationSnapshot.annotationPaperCount
                val tocSnapshot = contents?.let {
                    paginatePhysicalContents(it, key, AndroidContentsMeasurer(typefaces))
                }
                val tocRasterizer = contents?.let { AndroidContentsRasterizer(it, key, typefaces) }
                val notesSnapshot = notes?.let {
                    IncrementalPaginationEngine(AndroidBookMeasurer(it.document, missingImage,
                        typefaces = typefaces))
                        .paginate(it.document, position.noteLocation, key.forNotes()).last()
                }
                val notesRasterizer = notes?.let {
                    AndroidPageRasterizer(it.document, key.forNotes(), missingImage,
                        typefaces = typefaces)
                }
                val numbers = contents?.sourcePageNumbers(snapshot, notesSnapshot).orEmpty()
                val tocCount = tocSnapshot?.pages?.size ?: 0
                val mode = if (key.pageMode == PageMode.SPREAD) BookPresentation.SPREAD else BookPresentation.SINGLE
                position.restoreNativeTitle(document.titlePageBlockIds.toSet())
                val plan = bookSurfacePlan(position.initialState(snapshot, contents, tocSnapshot, notes, notesSnapshot, annotationSnapshot), mode,
                    snapshot.pages.size + annotationCount + tocCount + (notesSnapshot?.pages?.size ?: 0))
                val values = plan.required.associate { index ->
                    val paper = index - annotationCount
                    val labels = tocSnapshot?.pages?.getOrNull(paper)?.let { ContentsPageNumbers.forPage(it, numbers) }
                    ReaderSurfaceKey(index, labels) to if (index < annotationCount)
                        requireNotNull(annotationRasterizer).render(requireNotNull(annotationSnapshot).pages[index])
                    else if (paper < tocCount)
                        requireNotNull(tocRasterizer).render(requireNotNull(tocSnapshot).pages[paper], requireNotNull(labels))
                    else if (paper < tocCount + snapshot.pages.size) rasterizer.render(snapshot.pages[paper - tocCount])
                    else requireNotNull(notesRasterizer).render(requireNotNull(notesSnapshot).pages[paper - tocCount - snapshot.pages.size])
                }
                created.updateCache(SurfaceStoreState(values, values.values.sumOf { it.byteCount }, values.size.toLong()))
                created.updateIndex(snapshot, position, requestSurfaces = false, tocSnapshot = tocSnapshot, notesSnapshot = notesSnapshot,
                    annotationSnapshot = annotationSnapshot)
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
            val annotationSnapshot = annotation?.let {
                kotlinx.coroutines.withContext(Dispatchers.Default) {
                    IncrementalPaginationEngine(AndroidBookMeasurer(it.document, missingImage,
                        typefaces = typefaces))
                        .paginate(it.document, position.titleLocation ?: position.annotationLocation, key.forAnnotation()).last()
                }
            }
            val tocSnapshot = contents?.let {
                kotlinx.coroutines.withContext(Dispatchers.Default) {
                    paginatePhysicalContents(it, key, AndroidContentsMeasurer(typefaces))
                }
            }
            coroutineScope {
                val notesPending = async(Dispatchers.Default) {
                    notes?.let {
                        IncrementalPaginationEngine(AndroidBookMeasurer(it.document, missingImage,
                            typefaces = typefaces))
                            .paginate(it.document, position.noteLocation, key.forNotes()).last()
                    }
                }
                IncrementalPaginationEngine(AndroidBookMeasurer(document, missingImage,
                    typefaces = typefaces))
                    .paginate(document, position.location, key)
                    .flowOn(Dispatchers.Default)
                    .collect { snapshot ->
                        if (snapshot.complete && notes != null) {
                            // Source leaves can open immediately; the last cover is unavailable
                            // until the entire physical tail has a stable index.
                            session.updateIndex(snapshot.copy(complete = false), position, tocSnapshot = tocSnapshot, annotationSnapshot = annotationSnapshot)
                            session.updateIndex(snapshot, position, tocSnapshot = tocSnapshot,
                                notesSnapshot = notesPending.await(), annotationSnapshot = annotationSnapshot)
                        } else session.updateIndex(snapshot, position, tocSnapshot = tocSnapshot, annotationSnapshot = annotationSnapshot)
                    }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            session.failed = true
        }
    }
    return session
}

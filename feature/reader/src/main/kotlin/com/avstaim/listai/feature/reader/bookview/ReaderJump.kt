package com.avstaim.listai.feature.reader.bookview

import com.avstaim.listai.core.model.ReadingProgress
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.NoteId
import com.avstaim.listai.reader.pagination.PaginationSnapshot
import com.avstaim.listai.reader.pagination.pageIndexContaining
import com.avstaim.listai.reader.rendering.turn.TurnDirection

/** A one-shot command; its checkpoint is applied only after the physical leaf settles. */
internal data class ReaderJumpRequest(val sequence: Long, val progress: ReadingProgress,
    val noteId: NoteId? = null, val origin: BookLocation? = null, val noteTarget: BookLocation? = null,
    val returningFromNote: Boolean = false)

internal data class PreparedReaderJump(val request: ReaderJumpRequest, val target: PhysicalBookState.Open)

/** A partial index must never silently clamp a distant bookmark to its current last page. */
internal fun resolveReaderJump(
    request: ReaderJumpRequest,
    snapshot: PaginationSnapshot,
    contents: PhysicalContents?,
    tocSnapshot: PaginationSnapshot?,
    presentation: BookPresentation,
    notes: PhysicalNotes? = null,
    notesSnapshot: PaginationSnapshot? = null,
    annotationPageCount: Int = 0,
): PreparedReaderJump? {
    val progress = request.progress
    val tocCount = annotationPageCount + (tocSnapshot?.pages?.size ?: 0)
    val tocEntry = progress.tocEntryIndex
    val targetIndex = if (request.noteId != null) {
        if (!snapshot.complete || notes == null || notesSnapshot == null) return null
        val location = request.noteTarget ?: notes.targets[request.noteId] ?: return null
        tocCount + snapshot.pages.size + notesSnapshot.pages.pageIndexContaining(location)
    } else if (tocEntry != null) {
        if (contents == null || tocSnapshot == null) return null
        annotationPageCount + tocSnapshot.pages.pageIndexContaining(contents.location(tocEntry, progress.tocCharOffset))
    } else {
        val location = progress.location ?: return null
        if (!snapshot.complete && location.fallbackGlobalOffset >= snapshot.pages.last().endExclusive.fallbackGlobalOffset) return null
        tocCount + snapshot.pages.pageIndexContaining(location)
    }
    val target = PhysicalBookState.Open(targetIndex)
    // A right-hand leaf that has not been indexed yet cannot be substituted by a back cover.
    val count = tocCount + snapshot.pages.size + if (snapshot.complete) (notesSnapshot?.pages?.size ?: 0) else 0
    val anchor = requireNotNull(PhysicalBookNavigator(count).layoutAnchor(target, presentation))
    if (!snapshot.complete && presentation == BookPresentation.SPREAD && anchor > 0 && anchor + 1 >= count) return null
    return PreparedReaderJump(request, target)
}

/** TOC leaves precede the source even though their fallback source offsets are unrelated. */
internal fun readerJumpDirection(
    source: PhysicalBookState,
    target: PhysicalBookState.Open,
    current: ReadingProgress,
    requested: ReadingProgress,
): TurnDirection = when (source) {
    PhysicalBookState.ClosedFront, PhysicalBookState.FrontBoundary -> TurnDirection.FORWARD
    PhysicalBookState.ClosedBack, PhysicalBookState.BackBoundary -> TurnDirection.BACKWARD
    is PhysicalBookState.Open -> when {
        target.semanticPageIndex < source.semanticPageIndex -> TurnDirection.BACKWARD
        target.semanticPageIndex > source.semanticPageIndex -> TurnDirection.FORWARD
        requested.tocEntryIndex != null && current.tocEntryIndex != null ->
            if (compareValuesBy(requested, current, { it.tocEntryIndex }, { it.tocCharOffset }) < 0)
                TurnDirection.BACKWARD else TurnDirection.FORWARD
        (requested.location?.fallbackGlobalOffset ?: 0) < (current.location?.fallbackGlobalOffset ?: 0) -> TurnDirection.BACKWARD
        else -> TurnDirection.FORWARD
    }
}

/** Long jumps own at most two visible spreads, regardless of the distance travelled. */
internal fun readerJumpSurfaceIndices(source: PhysicalBookState, target: PhysicalBookState,
    presentation: BookPresentation, pageCount: Int): List<Int> =
    (visiblePageIndices(source, presentation, pageCount) + visiblePageIndices(target, presentation, pageCount)).distinct()

/** Drop offscreen bitmap references before pinning a source while its destination prepares. */
internal fun PreparedPagination.visibleOnly(state: PhysicalBookState, presentation: BookPresentation): PreparedPagination {
    val indices = visiblePageIndices(state, presentation, surfaces.size)
    return PreparedPagination(snapshot, BookSurfaceWindow(surfaces.size,
        indices.mapNotNull { index -> surfaces[index]?.let { index to it } }.toMap()),
        cacheBytes, cachedPages, renderedPages, contents, tocSnapshot, interactionToken,
        notes = notes, notesSnapshot = notesSnapshot,
        annotationSnapshot = annotationSnapshot, insideFront = insideFront)
}

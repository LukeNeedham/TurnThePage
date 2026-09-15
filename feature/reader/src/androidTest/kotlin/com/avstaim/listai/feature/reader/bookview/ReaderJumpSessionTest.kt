package com.avstaim.listai.feature.reader.bookview

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avstaim.listai.core.model.*
import com.avstaim.listai.reader.pagination.*
import com.avstaim.listai.reader.rendering.cache.SurfaceStoreState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.CoroutineContext

/** Drives the existing cache-publication boundary without adding fault hooks to the reader. */
@RunWith(AndroidJUnit4::class)
class ReaderJumpSessionTest {
    @Test fun failedDestinationPreservesSourceAndLatePixelsCannotReplayACancelledJump() = runBlocking {
        withSession {
            val source = requireNotNull(session.ready)
            val checkpoint = position.progress(length)
            val sourceBitmap = source.surfaces[state.semanticPageIndex]?.paginated?.bitmap
            val target = snapshot.pages.lastIndex
            session.requestJump(request(11, target), source, state, BookPresentation.SINGLE)
            assertSame(source, session.ready)
            session.updateCache(cache(sourceValues, failed = setOf(ReaderSurfaceKey(target))))
            assertEquals(11L, session.failedJumpSequence)
            assertEquals(checkpoint, position.progress(length))
            assertSame(sourceBitmap, session.ready?.surfaces?.get(state.semanticPageIndex)?.paginated?.bitmap)

            // The host observes the failed sequence and aborts this exact pending transaction.
            session.cancelJump()
            assertNull(session.failedJumpSequence)
            assertNull(session.ready?.jump)
            assertEquals(checkpoint, position.progress(length))
            val latePixels = sourceValues + (ReaderSurfaceKey(target) to rasterizer.render(snapshot.pages[target]))
            session.updateCache(cache(latePixels))
            assertNull(session.ready?.jump)
            assertEquals(checkpoint, position.progress(length))
            assertSame(sourceBitmap, session.ready?.surfaces?.get(state.semanticPageIndex)?.paginated?.bitmap)
            assertTrue(requireNotNull(session.ready).cacheBytes <= PAGE_CACHE_BYTES)
        }
    }

    @Test fun aSupersedingRequestOnlyPublishesItsOwnDestinationAndDoesNotMoveTheCheckpoint() = runBlocking {
        withSession {
            val source = requireNotNull(session.ready)
            val checkpoint = position.progress(length)
            val obsoleteTarget = snapshot.pages.lastIndex
            val currentTarget = obsoleteTarget - 2
            session.requestJump(request(21, obsoleteTarget), source, state, BookPresentation.SINGLE)
            session.requestJump(request(22, currentTarget), source, state, BookPresentation.SINGLE)
            val obsoletePixels = sourceValues + (ReaderSurfaceKey(obsoleteTarget) to rasterizer.render(snapshot.pages[obsoleteTarget]))
            session.updateCache(cache(obsoletePixels))
            assertNull(session.ready?.jump)
            assertEquals(checkpoint, position.progress(length))
            session.updateCache(cache(obsoletePixels +
                (ReaderSurfaceKey(currentTarget) to rasterizer.render(snapshot.pages[currentTarget]))))
            val destination = requireNotNull(session.ready?.jump)
            assertEquals(22L, destination.request.sequence)
            assertEquals(PhysicalBookState.Open(currentTarget), destination.target)
            assertEquals(checkpoint, position.progress(length))
            session.cancelJump()
            assertNull(session.ready?.jump)
            assertEquals(checkpoint, position.progress(length))
        }
    }

    @Test fun cancellationFromTheOwningLayoutKeepsItsCurrentPoseAndCloseDropsTheDestination() = runBlocking {
        withSession {
            val source = requireNotNull(session.ready)
            val checkpoint = position.progress(length)
            session.requestJump(request(31, snapshot.pages.lastIndex), source, state, BookPresentation.SINGLE)
            session.cancelJump()
            assertNull(session.ready?.jump)
            assertNotNull(session.ready?.surfaces?.get(state.semanticPageIndex))
            assertEquals(checkpoint, position.progress(length))
            session.requestJump(request(32, snapshot.pages.lastIndex), requireNotNull(session.ready), state,
                BookPresentation.SINGLE)
            session.close()
            session.cancelJump() // Disposal may close the owner before removing its renderer.
            assertNull(session.ready)
            assertEquals(checkpoint, position.progress(length))
        }
    }

    private suspend fun withSession(test: suspend Fixture.() -> Unit) {
        val fixture = Fixture.create()
        try { fixture.test() } finally { fixture.close() }
    }

    private class Fixture private constructor(
        val snapshot: PaginationSnapshot,
        val rasterizer: AndroidPageRasterizer,
        val session: PaginationSession,
        val position: ReaderPosition,
        val state: PhysicalBookState.Open,
        val sourceValues: Map<ReaderSurfaceKey, PaginatedPageSurface>,
        val length: Long,
        private val owner: CoroutineScope,
        private val dispatcher: PausedOwnerDispatcher,
    ) {
        fun request(sequence: Long, page: Int) = ReaderJumpRequest(sequence,
            ReadingProgress(snapshot.pages[page].start, CoverPosition.OPEN))

        fun close() {
            session.close()
            owner.cancel()
            dispatcher.drainCancelledWork()
        }

        companion object {
            suspend fun create(): Fixture {
                val document = BookDocument(BookId("pending-jump"), BookMetadata("Pending ink"),
                    List(8) { index -> BookBlock.Paragraph(BlockId("source-$index"),
                        listOf(InlineNode.Text("A real page keeps its ink until the next leaf is ready. ".repeat(20)))) })
                val key = LayoutKey(240, 380, PageMode.SINGLE)
                val snapshot = IncrementalPaginationEngine(AndroidBookMeasurer(document, "Missing"))
                    .paginate(document, null, key).last()
                assertTrue(snapshot.pages.size > 8)
                val state = PhysicalBookState.Open(2)
                val position = ReaderPosition(snapshot.pages[state.semanticPageIndex].start)
                val dispatcher = PausedOwnerDispatcher()
                val owner = CoroutineScope(SupervisorJob() + dispatcher)
                val session = PaginationSession(key, document, "Missing", owner)
                val rasterizer = AndroidPageRasterizer(document, key, "Missing")
                val values = bookSurfacePlan(state, BookPresentation.SINGLE, snapshot.pages.size).required.associate {
                    ReaderSurfaceKey(it) to rasterizer.render(snapshot.pages[it])
                }
                session.updateIndex(snapshot, position, requestSurfaces = false)
                session.updateCache(cache(values))
                return Fixture(snapshot, rasterizer, session, position, state, values, document.readingLength(), owner, dispatcher)
            }
        }
    }

    /** Raster completion/failure is supplied explicitly through PaginationSession.updateCache. */
    private class PausedOwnerDispatcher : CoroutineDispatcher() {
        private val work = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.addLast(block) }
        fun drainCancelledWork() { while (work.isNotEmpty()) work.removeFirst().run() }
    }

    private companion object {
        fun cache(values: Map<ReaderSurfaceKey, PaginatedPageSurface>, failed: Set<ReaderSurfaceKey> = emptySet()) =
            SurfaceStoreState(values, values.values.sumOf { it.byteCount }, values.size.toLong(), failedKeys = failed)
    }
}

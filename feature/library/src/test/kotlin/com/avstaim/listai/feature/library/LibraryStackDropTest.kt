package com.avstaim.listai.feature.library

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.avstaim.listai.core.model.*
import org.junit.Assert.*
import org.junit.Test

class LibraryStackDropTest {
    private val from = Rect(250f, 140f, 350f, 290f)
    private val destination = Rect(20f, 30f, 120f, 180f)

    @Test fun releasePreservesEveryCornerOfTheHeldCoverInsteadOfRestartingAtItsShelfSlot() {
        val frame = stackDropFrame(from, destination, 2, 0f)
        assertEquals(Offset(248f, 137f), frame.movingBook.backLeft)
        assertEquals(Offset(352f, 137f), frame.movingBook.backRight)
        assertEquals(Offset(352f, 293f), frame.movingBook.frontRight)
        assertEquals(Offset(248f, 293f), frame.movingBook.frontLeft)
        assertEquals(0f, frame.movingBook.thickness)
    }

    @Test fun addedBookIsTheTopCoverAtEveryStackSizeAndShelfScale() {
        for (count in 1..8) for (scale in listOf(.5f, 1f, 2.5f)) {
            val target = Rect(20f, 30f, 20f + 100f * scale, 30f + 150f * scale)
            val frame = stackDropFrame(from, target, count, 1f)
            val settled = stackBookGeometry(target.width, target.height, count + 1)
            val expectedPreviousTop = settled[settled.lastIndex - 1]
            val expectedAdded = settled.last()
            assertPoint(expectedPreviousTop.backLeft + target.topLeft, frame.previousTopBook.backLeft)
            assertPoint(expectedPreviousTop.frontRight + target.topLeft, frame.previousTopBook.frontRight)
            assertPoint(expectedAdded.backLeft + target.topLeft, frame.movingBook.backLeft)
            assertPoint(expectedAdded.frontRight + target.topLeft, frame.movingBook.frontRight)
        }
    }

    @Test fun flightStaysConvexAndContinuousAboveTheStack() {
        var previous = stackDropFrame(from, destination, 2, 0f)
        for (step in 1..100) {
            val next = stackDropFrame(from, destination, 2, step / 100f)
            val corners = with(next.movingBook) { listOf(backLeft, backRight, frontRight, frontLeft) }
            assertTrue(corners.all { it.x.isFinite() && it.y.isFinite() })
            assertTrue(corners.zip(corners.drop(1) + corners.first()).all { (a, b) -> (b - a).getDistance() > 0f })
            val cross = corners.indices.map { index ->
                val a = corners[(index + 1) % 4] - corners[index]
                val b = corners[(index + 2) % 4] - corners[(index + 1) % 4]
                a.x * b.y - a.y * b.x
            }
            assertTrue("A rigid book must not fold inside out", cross.all { it > 0f })
            assertTrue((next.movingBook.frontLeft - previous.movingBook.frontLeft).getDistance() < 6f)
            previous = next
        }
    }

    @Test fun roomRemovalCannotDiscardTheRetainedCoverAndPartialMembershipIsNotConfirmation() {
        val incoming = book("incoming")
        val top = book("top").copy(stackId = "stack")
        val original = LibraryCatalog(listOf(incoming, top), listOf(LibraryStack("stack", "Stack", listOf(top.id))),
            listOf(LibraryShelfItem.Book(incoming.id), LibraryShelfItem.Stack("stack")))
        val drop = LibraryStackDrop(incoming, LibraryTile(LibraryShelfItem.Stack("stack"), top, original.stacks.single()),
            from, destination, original)
        val committed = original.copy(books = listOf(incoming.copy(stackId = "stack"), top),
            stacks = listOf(original.stacks.single().copy(bookIds = listOf(incoming.id, top.id))),
            shelf = listOf(LibraryShelfItem.Stack("stack")))
        assertFalse(drop.isCommittedIn(original))
        assertFalse(drop.isCommittedIn(committed.copy(stacks = original.stacks)))
        assertFalse(drop.isCommittedIn(committed.copy(books = original.books)))
        assertFalse(drop.isCommittedIn(committed.copy(stacks = listOf(committed.stacks.single().copy(bookIds = listOf(top.id, incoming.id))))))
        assertTrue(drop.isCommittedIn(committed))
        assertEquals(incoming, drop.book)
        assertEquals(original.shelf, drop.shelf.shelf)
        assertEquals(top.id, drop.target.book?.id)
    }

    @Test fun outOfRangeProgressDoesNotOvershootItsEndpoints() {
        assertEquals(stackDropFrame(from, destination, 2, 0f), stackDropFrame(from, destination, 2, -1f))
        assertEquals(stackDropFrame(from, destination, 2, 1f), stackDropFrame(from, destination, 2, 2f))
    }

    @Test fun removingAnEarlierSlotMovesTheWholeLandedStackContinuouslyIntoTheNewRow() {
        val oldSlot = Rect(220f, 240f, 320f, 390f)
        val newSlot = Rect(20f, 30f, 120f, 180f)
        assertEquals(oldSlot, stackDropDestination(oldSlot, newSlot, 0f))
        assertEquals(newSlot, stackDropDestination(oldSlot, newSlot, 1f))
        val middle = stackDropDestination(oldSlot, newSlot, .5f)
        assertEquals(100f, middle.width); assertEquals(150f, middle.height)
        assertEquals(Offset(170f, 210f), middle.center)
        val first = stackDropFrame(from, oldSlot, 2, 1f)
        val second = stackDropFrame(from, middle, 2, 1f)
        val delta = middle.topLeft - oldSlot.topLeft
        assertPoint(first.previousTopBook.frontLeft + delta, second.previousTopBook.frontLeft)
        assertPoint(first.movingBook.backRight + delta, second.movingBook.backRight)
    }

    private fun assertPoint(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, .0001f); assertEquals(expected.y, actual.y, .0001f)
    }
    private fun book(id: String) = LibraryBook(BookId(id), id, "", "$id.fb2", null, 0, null, 0, ReadingProgress())
}

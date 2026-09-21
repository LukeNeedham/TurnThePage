package com.avstaim.listai

import com.avstaim.listai.core.model.BlockId
import com.avstaim.listai.core.model.BookLocation
import com.avstaim.listai.core.model.CoverPosition
import com.avstaim.listai.core.model.ReadingProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ShelfProgressTest {
    private val location = BookLocation(BlockId("last-page"), charOffset = 19, fallbackGlobalOffset = 8_400)

    @Test fun `rear cover poses reset to the external front cover without losing semantic anchors`() {
        listOf(CoverPosition.BACK, CoverPosition.BOUNDARY).forEach { cover ->
            val original = ReadingProgress(location = location, cover = cover, fraction = 1f,
                tocEntryIndex = 2, tocCharOffset = 7, annotationLocation = location)
            val shelved = original.resetRearCoverForShelf()

            assertEquals(original.copy(cover = CoverPosition.FRONT), shelved)
        }
    }

    @Test fun `ordinary reading and front cover poses are not rewritten`() {
        listOf(CoverPosition.OPEN, CoverPosition.FRONT).forEach { cover ->
            val original = ReadingProgress(location = location, cover = cover, fraction = .42f)
            assertSame(original, original.resetRearCoverForShelf())
        }
    }
}

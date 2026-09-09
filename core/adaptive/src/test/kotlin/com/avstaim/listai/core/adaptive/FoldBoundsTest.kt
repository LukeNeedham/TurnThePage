package com.avstaim.listai.core.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FoldBoundsTest {
    @Test
    fun `dimensions and center are derived without losing half pixels`() {
        val bounds = FoldBounds(left = 10, top = 20, right = 15, bottom = 27)

        assertEquals(5, bounds.width)
        assertEquals(7, bounds.height)
        assertEquals(FoldCenter(x = 12.5, y = 23.5), bounds.center)
    }

    @Test
    fun `inverted horizontal bounds are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            FoldBounds(left = 2, top = 0, right = 1, bottom = 1)
        }
    }

    @Test
    fun `inverted vertical bounds are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            FoldBounds(left = 0, top = 2, right = 1, bottom = 1)
        }
    }
}

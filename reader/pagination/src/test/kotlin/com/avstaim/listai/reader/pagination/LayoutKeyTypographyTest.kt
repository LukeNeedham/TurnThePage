package com.avstaim.listai.reader.pagination

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LayoutKeyTypographyTest {
    @Test
    fun plyosMetricsHaveAStableDefaultCacheIdentity() {
        val plyos = LayoutKey(400, 700, PageMode.SINGLE)
        assertEquals("plyos-0.100", plyos.fontFamilyId)
        assertNotEquals(plyos, plyos.copy(fontFamilyId = "serif"))
    }
}

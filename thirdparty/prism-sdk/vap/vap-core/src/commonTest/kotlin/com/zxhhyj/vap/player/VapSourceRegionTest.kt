package com.zxhhyj.vap.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class VapSourceRegionTest {
    @Test
    fun acceptsFileAndAssetRegionsWithValueIdentity() {
        val file = VapSource.AbsolutePath("/clip.prism")
        val region = VapSource.Region(file, 128L, 4096L)
        assertEquals(file, region.source)
        assertEquals(128L, region.offset)
        assertEquals(4096L, region.length)
        assertEquals(region, region.copy())
        assertNotEquals(region, region.copy(offset = 129L))
        assertNotEquals(region, region.copy(length = 4095L))
        assertEquals(VapSource.Asset("clip.prism"), VapSource.Region(VapSource.Asset("clip.prism"), 0L, 1L).source)
    }

    @Test
    fun rejectsBytesAndNestedRegions() {
        assertFailsWith<IllegalArgumentException> { VapSource.Region(VapSource.Bytes(byteArrayOf(1)), 0L, 1L) }
        val region = VapSource.Region(VapSource.AbsolutePath("/clip.prism"), 0L, 1L)
        assertFailsWith<IllegalArgumentException> { VapSource.Region(region, 0L, 1L) }
    }

    @Test
    fun validatesSignsAndEndWithoutOverflow() {
        val source = VapSource.AbsolutePath("/clip.prism")
        assertFailsWith<IllegalArgumentException> { VapSource.Region(source, -1L, 1L) }
        assertFailsWith<IllegalArgumentException> { VapSource.Region(source, 0L, 0L) }
        assertFailsWith<IllegalArgumentException> { VapSource.Region(source, 0L, -1L) }
        assertFailsWith<IllegalArgumentException> { VapSource.Region(source, Long.MAX_VALUE, 1L) }
        assertFailsWith<IllegalArgumentException> { VapSource.Region(source, 1L, Long.MAX_VALUE) }
        assertEquals(Long.MAX_VALUE, VapSource.Region(source, 0L, Long.MAX_VALUE).length)
        assertEquals(Long.MAX_VALUE - 1L, VapSource.Region(source, Long.MAX_VALUE - 1L, 1L).offset)
    }
}

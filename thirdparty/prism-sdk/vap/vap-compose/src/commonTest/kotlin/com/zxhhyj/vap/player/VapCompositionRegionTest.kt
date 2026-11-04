package com.zxhhyj.vap.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VapCompositionRegionTest {
    @Test
    fun fileAndAssetRegionsRoundTripWithoutLosingBounds() {
        val sources = listOf(VapSource.AbsolutePath("/clip.prism"), VapSource.Asset("clip.prism"))
        for (source in sources) {
            val region = VapSource.Region(source, 123L, 456L)
            val spec = region.toCompositionSpec() as VapCompositionSpec.Region
            assertEquals(123L, spec.offset)
            assertEquals(456L, spec.length)
            assertEquals(region, spec.toVapSource())
            assertEquals(spec, spec.toVapSource().toCompositionSpec())
        }
    }

    @Test
    fun rejectsInvalidCompositionRegionsBeforeConversion() {
        val file = VapCompositionSpec.File("/clip.prism")
        assertFailsWith<IllegalArgumentException> { VapCompositionSpec.Region(file, -1L, 1L) }
        assertFailsWith<IllegalArgumentException> { VapCompositionSpec.Region(file, 0L, 0L) }
        assertFailsWith<IllegalArgumentException> { VapCompositionSpec.Region(file, 1L, Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { VapCompositionSpec.Region(VapCompositionSpec.Bytes(byteArrayOf(1)), 0L, 1L) }
        assertFailsWith<IllegalArgumentException> { VapCompositionSpec.Region(VapCompositionSpec.Region(file, 0L, 1L), 0L, 1L) }
    }
}

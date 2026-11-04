package com.zxhhyj.vap.decode

import com.zxhhyj.vap.player.VapSource
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VapAssetRegionJvmTest {
    @Test
    fun assetRegionMetadataClearlyRejectsUnsupportedPlatform() {
        val error = assertFailsWith<IllegalStateException> {
            parseMp4Region(VapSource.Region(VapSource.Asset("clip.prism"), 12L, 24L))
        }
        assertTrue(error.message.orEmpty().contains("Android-only"))
    }
}

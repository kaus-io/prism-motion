package com.zxhhyj.vap.decode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VapPresentationRateTest {
    @Test
    fun lowerRateKeepsSourceTimeline() {
        assertEquals(30, VapPresentationRate.timelineFps(30, 24))
        assertEquals(30, VapPresentationRate.timelineFps(30, 30))
        assertEquals(60, VapPresentationRate.timelineFps(30, 60))
        assertEquals(0, VapPresentationRate.timelineFps(30, 0))
    }

    @Test
    fun tenSecondClipKeeps240FramesWithoutChangingDuration() {
        val kept = (0 until 300).distinctBy { frame ->
            VapPresentationRate.bucket(frame * 1_000_000L / 30, 24)
        }
        assertEquals(240, kept.size)
        assertEquals(0, kept.first())
        assertTrue(kept.last() >= 298)
        // All 300 source frames retain their 30fps timeline, rather than taking 12.5s.
        assertEquals(10_000_000L, 300 * 1_000_000L / VapPresentationRate.timelineFps(30, 24))
    }

    @Test
    fun ptsRoundingAndNonzeroStartDoNotLoseBuckets() {
        val offset = 8_000_000L
        val indices = (0 until 30).map { frame ->
            val pts = offset + frame * 1_000_000L / 30
            VapPresentationRate.bucket(pts - offset, 24)
        }
        assertEquals(24, indices.distinct().size)
        assertEquals(4L, VapPresentationRate.bucket(166_666L, 24))
        assertEquals(0L, VapPresentationRate.bucket(-1L, 24))
    }
}

package com.zxhhyj.kap.compose

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KtxCadenceProbeTest {
    @Test fun stableCadenceHasNoSkippedOrLateFrames() {
        val probe = KtxCadenceProbe(30)
        repeat(150) { assertNull(probe.record(true, it.toLong(), 1L + it * 33_333_333L)) }
        val report = probe.record(true, 150, 1L + 150 * 33_333_333L)!!
        assertTrue(report.contains("avg_us=33333"))
        assertTrue(report.contains("skipped=0"))
        assertTrue(report.contains("late=0"))
    }

    @Test fun failedPresentAndSkippedTimelineFramesAreCounted() {
        val probe = KtxCadenceProbe(1)
        assertNull(probe.record(true, 0, 1))
        assertNull(probe.record(false, 1, 1_000_000_001))
        repeat(4) { assertNull(probe.record(true, it + 2L, 2_000_000_001L + it * 1_000_000_000L)) }
        val report = probe.record(true, 6, 6_000_000_001L)!!
        assertTrue(report.contains("unavailable=1"))
        assertTrue(report.contains("skipped=1"))
        assertTrue(report.contains("late=1"))
    }
}

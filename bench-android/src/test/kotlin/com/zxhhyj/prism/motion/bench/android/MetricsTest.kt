package com.zxhhyj.prism.motion.bench.android

import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证统计单位与刷新率阈值。 / Verifies statistic units and refresh-aware thresholds. */
class MetricsTest {
    @Test
    fun fitsLandscapeGridWithoutClippingOrInstantiatingOverflow() {
        assertEquals(GridGeometry(6, 3), gridFor(16, 2000, 950, 256))
        assertEquals(null, gridFor(16, 1024, 950, 256))
        assertEquals(GridGeometry(1, 0), gridFor(0, 0, 0, 128))
    }

    @Test
    fun reportsEmptyAndSingleFrameSamples() {
        assertEquals(0.0, FrameStats(emptyList()).fps, 0.0)
        val single = FrameStats(listOf(20_000))
        assertEquals(50.0, single.fps, 0.001)
        assertEquals(20.0, single.p95Ms, 0.001)
        assertEquals(0.0, single.frameStdMs, 0.0)
    }

    @Test
    fun jankThresholdFollowsRefreshRateAndStdIsMilliseconds() {
        val values = listOf(10_000L, 20_000L)
        assertEquals(0.0, FrameStats(values, 60.0).jankRatio, 0.0)
        assertEquals(0.5, FrameStats(values, 120.0).jankRatio, 0.0)
        assertEquals(5.0, FrameStats(values).frameStdMs, 0.001)
    }
}

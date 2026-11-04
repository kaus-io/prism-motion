package com.zxhhyj.prism.motion.bench.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证计数、边界、有界记录和独立播放器间隔。 / Verifies exact counts, boundaries, bounds and per-player intervals. */
class FrameTraceTest {
    @Test
    fun excludesCallbacksOutsideHalfOpenWindow() {
        val trace = FrameTrace()
        for (time in listOf(10L, 20L, 30L, 40L)) trace.onFramePresented(time)
        assertArrayEquals(longArrayOf(20L, 30L), trace.window(20, 40))
        assertEquals(4L, trace.count())
    }

    @Test
    fun capacityAndNonMonotonicEventsAreExplicitFailures() {
        val trace = FrameTrace(2)
        trace.onFramePresented(10)
        trace.onFramePresented(20)
        trace.onFramePresented(30)
        trace.onFramePresented(30)
        assertTrue(trace.overflow)
        assertTrue(trace.invalidTimestamp)
        assertEquals(3L, trace.count())
        assertArrayEquals(longArrayOf(10L, 20L), trace.window(0, 100))
    }

    @Test
    fun neverMixesStaggeredPlayersWhenComputingIntervals() {
        val stats = PresentStats(listOf(
            longArrayOf(0, 33_000_000, 66_000_000),
            longArrayOf(16_000_000, 49_000_000, 82_000_000),
        ), 0.1, 30.0)
        assertEquals(6, stats.frames)
        assertEquals(30.0, stats.fps, 0.001)
        assertEquals(33.0, stats.intervals.p95Ms, 0.001)
        assertTrue(stats.realtime)
    }

    @Test
    fun slowPlayersAreVisibleAndBaselineHasNoSyntheticFrames() {
        val stats = PresentStats(listOf(longArrayOf(0, 33_000_000), longArrayOf(0)), 0.1, 30.0)
        assertEquals(15.0, stats.fps, 0.001)
        assertEquals(10.0, stats.minimumFps, 0.001)
        assertFalse(stats.realtime)
        val baseline = PresentStats(emptyList(), 1.0, 30.0)
        assertEquals(0.0, baseline.fps, 0.0)
        assertEquals(0, baseline.frames)
        assertTrue(baseline.realtime)
    }
}

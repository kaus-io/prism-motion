package com.zxhhyj.prism.motion.compose

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 无窗口验证时钟、重复、缓存及释放。 / Windowless checks for clock pacing, repeats, cache, and release. */
class JvmFramePlayerTest {
    @Test
    fun timelineUsesElapsedTimeAndSkipsMissedFrames() {
        val timeline = JvmFrameTimeline(frameCount = 10, fps = 10)
        timeline.tick(1_000_000_000L)
        timeline.tick(1_050_000_000L)
        assertEquals(0, timeline.frameIndex)
        timeline.tick(1_100_000_000L)
        assertEquals(1, timeline.frameIndex)
        timeline.tick(1_450_000_000L)
        assertEquals(4, timeline.frameIndex)
        timeline.tick(1_400_000_000L)
        assertEquals(4, timeline.frameIndex)
        timeline.tick(1_500_000_000L)
        assertEquals(5, timeline.frameIndex)
    }

    @Test
    fun fractionalFrameRatesDoNotAccumulateTruncationDrift() {
        val timeline = JvmFrameTimeline(frameCount = 1_000, fps = 30)
        timeline.tick(0L)
        for (tick in 1L..600L) timeline.tick(tick * 1_000_000_000L / 60L)
        assertEquals(300, timeline.frameIndex)
    }

    @Test
    fun pauseAndSeekReanchorWithoutCountingWallTime() {
        val timeline = JvmFrameTimeline(frameCount = 10, fps = 10)
        timeline.tick(0L)
        timeline.tick(150_000_000L)
        assertEquals(1, timeline.frameIndex)
        timeline.pause()
        timeline.tick(10_000_000_000L)
        assertEquals(1, timeline.frameIndex)
        timeline.tick(10_050_000_000L)
        assertEquals(2, timeline.frameIndex)
        timeline.seekTo(7)
        timeline.tick(20_000_000_000L)
        assertEquals(7, timeline.frameIndex)
        timeline.tick(20_100_000_000L)
        assertEquals(8, timeline.frameIndex)
    }

    @Test
    fun finiteRepeatsStopOnLastFrameAndInfiniteRepeatsWrap() {
        val finite = JvmFrameTimeline(frameCount = 3, fps = 10)
        finite.setRepeatCount(1)
        finite.tick(0L)
        finite.tick(300_000_000L)
        assertEquals(0, finite.frameIndex)
        assertFalse(finite.isComplete)
        finite.tick(500_000_000L)
        assertEquals(2, finite.frameIndex)
        assertFalse(finite.isComplete)
        finite.tick(600_000_000L)
        assertEquals(2, finite.frameIndex)
        assertTrue(finite.isComplete)

        val infinite = JvmFrameTimeline(frameCount = 3, fps = 10)
        infinite.setRepeatCount(-1)
        infinite.tick(0L)
        infinite.tick(10_000_000_000L)
        assertEquals(1, infinite.frameIndex)
        assertFalse(infinite.isComplete)
        assertFailsWith<IllegalArgumentException> { infinite.setRepeatCount(-2) }
    }

    @Test
    fun hugeClockGapsClampFinitePlaybackWithoutOverflow() {
        val timeline = JvmFrameTimeline(frameCount = 3, fps = Int.MAX_VALUE)
        timeline.tick(0L)
        timeline.tick(Long.MAX_VALUE)
        assertEquals(2, timeline.frameIndex)
        assertTrue(timeline.isComplete)
    }

    @Test
    fun cacheRespectsByteBudgetAndAccessOrder() {
        var loads = 0
        val cache = JvmFrameCache<ByteArray>(maxBytes = 8L, maxEntries = 4) { it.size.toLong() }
        fun load(index: Int): ByteArray = cache.getOrPut(index) { loads++; ByteArray(4) }
        val first = load(0)
        load(1)
        assertSame(first, load(0))
        load(2)
        assertEquals(8L, cache.sizeBytes)
        assertEquals(2, cache.entryCount)
        assertSame(first, load(0))
        load(1)
        assertEquals(4, loads)
        cache.clear()
        assertEquals(0L, cache.sizeBytes)
        assertEquals(0, cache.entryCount)
    }

    @Test
    fun cacheBoundsEntryCountAndDoesNotRetainOversizedFrames() {
        val cache = JvmFrameCache<ByteArray>(maxBytes = 8L, maxEntries = 2) { it.size.toLong() }
        repeat(3) { index -> cache.getOrPut(index) { ByteArray(1) } }
        assertEquals(2, cache.entryCount)
        var oversizedLoads = 0
        repeat(2) { cache.getOrPut(9) { oversizedLoads++; ByteArray(9) } }
        assertEquals(2, oversizedLoads)
        assertEquals(2L, cache.sizeBytes)
        assertEquals(2, cache.entryCount)
    }

    @Test
    fun outputAndFrameCountsRequireDrawAcknowledgement() {
        val resource = TestResource()
        assertFalse(resource.hasOutput)
        assertEquals(0L, resource.presentedFrameCount)
        resource.play()
        resource.onFrameTime(0L)
        assertEquals(0L, resource.presentedFrameCount)
        resource.onFrameDrawn(resource.frameVersion)
        resource.onFrameDrawn(resource.frameVersion)
        assertTrue(resource.hasOutput)
        assertEquals(1L, resource.presentedFrameCount)
        val oldVersion = resource.frameVersion
        resource.onFrameTime(100_000_000L)
        resource.onFrameDrawn(oldVersion)
        assertEquals(1L, resource.presentedFrameCount)
        resource.onFrameDrawn(resource.frameVersion)
        assertEquals(2L, resource.presentedFrameCount)
        resource.detach()
        assertFalse(resource.hasOutput)
        resource.onFrameDrawn(resource.frameVersion)
        assertTrue(resource.hasOutput)
        assertEquals(3L, resource.presentedFrameCount)
    }

    @Test
    fun completionCanRestartAndReleaseIsTerminalAndIdempotent() {
        val resource = TestResource()
        resource.play()
        resource.onFrameTime(0L)
        resource.onFrameTime(200_000_000L)
        assertFalse(resource.isPlaying)
        assertEquals(1f, resource.progress)
        assertEquals(1, resource.frameIndex)
        resource.play()
        assertTrue(resource.isPlaying)
        assertEquals(0, resource.frameIndex)
        assertEquals(0f, resource.progress)
        resource.release()
        resource.release()
        resource.play()
        resource.seekTo(1f)
        resource.onFrameDrawn(resource.frameVersion)
        assertFalse(resource.isPlaying)
        assertFalse(resource.hasOutput)
        assertEquals(1, resource.releaseCalls)
        assertFailsWith<IllegalStateException> { resource.currentBitmap() }
    }

    @Test
    fun bitmapFailureStopsPlaybackAndIsObservable() {
        val resource = TestResource()
        resource.play()
        val error = assertFailsWith<IllegalStateException> { resource.currentBitmap() }
        assertSame(error, resource.playbackError)
        assertFalse(resource.hasOutput)
        assertFalse(resource.isPlaying)
        resource.play()
        assertFalse(resource.isPlaying)
    }

    @Test
    fun sharedFrameClockHasNoAwaitersWhilePausedAndClosesOnRelease() = runBlocking {
        val clock = BroadcastFrameClock()
        val resource = TestResource()
        resource.setRepeatCount(-1)
        val job = launch(Dispatchers.Unconfined + clock, start = CoroutineStart.UNDISPATCHED) {
            resource.runFrameClock()
        }
        try {
            assertFalse(clock.hasAwaiters)
            resource.play()
            assertTrue(clock.hasAwaiters)
            clock.sendFrame(0L)
            clock.sendFrame(100_000_000L)
            assertEquals(1, resource.frameIndex)
            resource.pause()
            assertFalse(clock.hasAwaiters)
            clock.sendFrame(10_000_000_000L)
            assertEquals(1, resource.frameIndex)
            resource.play()
            assertTrue(clock.hasAwaiters)
            clock.sendFrame(20_000_000_000L)
            assertEquals(1, resource.frameIndex)
            resource.release()
            job.join()
            assertFalse(clock.hasAwaiters)
            assertTrue(job.isCompleted)
        } finally {
            resource.release()
            job.cancel()
        }
    }

    @Test
    fun cancelledHostClockReanchorsOnReattachment() = runBlocking {
        val clock = BroadcastFrameClock()
        val resource = TestResource()
        resource.setRepeatCount(-1)
        resource.play()
        val first = launch(Dispatchers.Unconfined + clock, start = CoroutineStart.UNDISPATCHED) {
            resource.runFrameClock()
        }
        try {
            clock.sendFrame(0L)
            clock.sendFrame(100_000_000L)
            assertEquals(1, resource.frameIndex)
        } finally {
            first.cancelAndJoin()
        }
        assertFalse(clock.hasAwaiters)
        val replacement = launch(Dispatchers.Unconfined + clock, start = CoroutineStart.UNDISPATCHED) {
            resource.runFrameClock()
        }
        try {
            clock.sendFrame(20_000_000_000L)
            assertEquals(1, resource.frameIndex)
            clock.sendFrame(20_100_000_000L)
            assertEquals(0, resource.frameIndex)
        } finally {
            resource.release()
            replacement.cancel()
        }
    }

    @Test
    fun finiteCompletionStopsRequestingClockFrames() = runBlocking {
        val clock = BroadcastFrameClock()
        val resource = TestResource()
        resource.play()
        val job = launch(Dispatchers.Unconfined + clock, start = CoroutineStart.UNDISPATCHED) {
            resource.runFrameClock()
        }
        try {
            clock.sendFrame(0L)
            clock.sendFrame(200_000_000L)
            assertFalse(resource.isPlaying)
            assertEquals(1f, resource.progress)
            assertFalse(clock.hasAwaiters)
        } finally {
            resource.release()
            job.cancel()
        }
    }

    private class TestResource : JvmFrameResource(1, 1, frameCount = 2, fps = 10) {
        var releaseCalls = 0
            private set

        override fun renderFrame(index: Int): ImageBitmap = error("expected synthetic bitmap failure")
        override fun onRelease() { releaseCalls++ }
    }
}

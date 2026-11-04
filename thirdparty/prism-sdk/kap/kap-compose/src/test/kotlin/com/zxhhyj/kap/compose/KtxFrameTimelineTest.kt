package com.zxhhyj.kap.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtxFrameTimelineTest {
    @Test
    fun thirtyFpsUsesEveryOtherRefreshAtSixtyHz() {
        val frames = renderedFrames(fps = 30, refreshNumerator = 60, ticks = 601)
        assertEquals((0L..300L).toList(), frames.map { it.frame })
        assertTrue(frames.zipWithNext().all { (a, b) -> b.tick - a.tick == 2 })
    }

    @Test
    fun thirtyFpsUsesThreeAndFourRefreshesAtNinetyAndOneTwentyHz() {
        for (refresh in listOf(90, 120)) {
            val frames = renderedFrames(fps = 30, refreshNumerator = refresh, ticks = refresh * 3 + 1)
            assertEquals((0L..90L).toList(), frames.map { it.frame })
            assertTrue(frames.zipWithNext().all { (a, b) -> b.tick - a.tick == refresh / 30 })
        }
    }

    @Test
    fun twentyFourFpsUsesTwoOrThreeRefreshesAtSixtyHz() {
        val frames = renderedFrames(fps = 24, refreshNumerator = 60, ticks = 601)
        assertEquals((0L..240L).toList(), frames.map { it.frame })
        assertTrue(frames.zipWithNext().all { (a, b) -> b.tick - a.tick in 2..3 })
    }

    @Test
    fun fractionalRefreshDoesNotRepeatedlyMissFrameBoundaries() {
        val frames = renderedFrames(fps = 30, refreshNumerator = 60_000, refreshDenominator = 1001, ticks = 601)
        assertEquals(0L, frames.first().frame)
        assertEquals(300L, frames.last().frame)
        assertTrue(frames.zipWithNext().all { (a, b) -> b.frame - a.frame == 1L })
    }

    @Test
    fun lateVsyncSkipsObsoleteFramesInsteadOfQueuingThem() {
        val timeline = KtxFrameTimeline(30)
        assertEquals(0L, timeline.advance(1_000_000_000L, playing = true))
        assertEquals(6L, timeline.advance(1_200_000_000L, playing = true))
        assertTrue(timeline.nextDeadline(1_200_000_000L, 6) > 1_200_000_000L)
    }

    @Test
    fun pauseExcludesTimeSpentPausedAndResumeKeepsPosition() {
        val timeline = KtxFrameTimeline(30)
        assertEquals(0L, timeline.advance(0, playing = true))
        assertEquals(3L, timeline.advance(100_000_000, playing = true))
        timeline.pause()
        assertEquals(3L, timeline.advance(9_000_000_000, playing = true))
        assertEquals(4L, timeline.advance(9_033_333_333, playing = true))
    }

    @Test
    fun initiallyPausedAlwaysSelectsActualFrameZero() {
        val timeline = KtxFrameTimeline(30)
        assertEquals(0L, timeline.advance(1_000_000_000, playing = false))
        assertEquals(0L, timeline.advance(9_000_000_000, playing = false))
        assertEquals(0L, timeline.advance(10_000_000_000, playing = true))
        assertEquals(1L, timeline.advance(10_033_333_333, playing = true))
    }

    @Test
    fun restartAndRegressingTimestampsDoNotAdvanceUnexpectedly() {
        val timeline = KtxFrameTimeline(30)
        timeline.advance(0, playing = true)
        timeline.advance(100_000_000, playing = true)
        assertEquals(3L, timeline.advance(90_000_000, playing = true))
        assertEquals(3L, timeline.advance(100_000_000, playing = true))
        timeline.restart()
        assertEquals(0L, timeline.advance(4_000_000_000, playing = true))
    }

    @Test
    fun displayRateChangesKeepAnimationTimeContinuous() {
        val timeline = KtxFrameTimeline(30)
        timeline.advance(0, playing = true)
        assertEquals(30L, timeline.advance(1_000_000_000, playing = true))
        assertEquals(30L, timeline.advance(1_008_333_333, playing = true))
        assertEquals(31L, timeline.advance(1_033_333_333, playing = true))
    }

    @Test
    fun seekRepositionsWithoutAdvancingAndContinuesFromThere() {
        val timeline = KtxFrameTimeline(30)
        timeline.seekTo(90)
        // Paused seek still reports the target frame.
        assertEquals(90L, timeline.advance(1_000_000_000, playing = false))
        // Resuming continues one frame later instead of restarting.
        assertEquals(90L, timeline.advance(2_000_000_000, playing = true))
        assertEquals(91L, timeline.advance(2_033_333_333, playing = true))
    }

    @Test
    fun seekBackwardsReplaysFromTheNewFrame() {
        val timeline = KtxFrameTimeline(30)
        timeline.advance(0, playing = true)
        assertEquals(30L, timeline.advance(1_000_000_000, playing = true))
        timeline.seekTo(10)
        assertEquals(10L, timeline.advance(1_500_000_000, playing = true))
    }

    @Test
    fun eagerOutputRepaintLeavesFirstVsyncAtTheSameFrame() {
        val timeline = KtxFrameTimeline(30)
        timeline.seekTo(42)
        // Output creation paints now, without pretending this is a display tick.
        assertEquals(42L, timeline.advance(1_003_000_000, playing = false))
        assertEquals(1_400_000_000L, timeline.elapsedNanos)
        // Even delayed Main dispatch must anchor at the current frame, not skip it.
        val firstVsync = 1_100_000_000L
        assertEquals(42L, timeline.advance(firstVsync, playing = true))
        assertEquals(42L, timeline.advance(firstVsync + 16_666_667, playing = true))
        assertEquals(43L, timeline.advance(firstVsync + 33_333_333, playing = true))
    }

    @Test
    fun pausedEagerOutputRepaintKeepsSeekPositionUntilResume() {
        val timeline = KtxFrameTimeline(30)
        timeline.seekTo(90)
        assertEquals(90L, timeline.advance(1_000_000_000, playing = false))
        timeline.pause()
        assertEquals(90L, timeline.advance(9_000_000_000, playing = true))
        val deadline = timeline.nextDeadline(9_000_000_000, 90)
        assertTrue(deadline > 9_000_000_000)
        assertEquals(91L, timeline.advance(deadline + 1, playing = true))
    }

    private data class RenderedFrame(val tick: Int, val frame: Long)

    private fun renderedFrames(
        fps: Int,
        refreshNumerator: Int,
        refreshDenominator: Int = 1,
        ticks: Int,
    ): List<RenderedFrame> {
        val timeline = KtxFrameTimeline(fps)
        var due = 0L
        var previous = -1L
        return buildList {
            repeat(ticks) { tick ->
                val timestamp = tick * 1_000_000_000L * refreshDenominator / refreshNumerator
                if (timestamp >= due) {
                    val frame = timeline.advance(timestamp, playing = true)
                    if (frame != previous) {
                        add(RenderedFrame(tick, frame))
                        previous = frame
                    }
                    due = timeline.nextDeadline(timestamp, frame)
                }
            }
        }
    }
}

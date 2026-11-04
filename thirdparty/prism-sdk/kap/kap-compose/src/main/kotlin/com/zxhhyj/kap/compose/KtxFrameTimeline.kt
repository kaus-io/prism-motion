package com.zxhhyj.kap.compose

/** Animation time advances only from display vsync timestamps, never render duration. */
internal class KtxFrameTimeline(private val fps: Int) {
    init {
        require(fps > 0)
    }

    var elapsedNanos: Long = 0
        private set

    private var lastVsync = Long.MIN_VALUE
    // Avoid an extra refresh due to integer rounding / fractional display rates
    // when a nominal 33.333ms frame lands a few microseconds before its deadline.
    private val boundaryTolerance = minOf(1_000_000L, 250_000_000L / fps)

    fun advance(vsyncNanos: Long, playing: Boolean): Long {
        if (playing && lastVsync != Long.MIN_VALUE) {
            elapsedNanos += (vsyncNanos - lastVsync).coerceAtLeast(0)
        }
        lastVsync = when {
            !playing -> Long.MIN_VALUE
            lastVsync == Long.MIN_VALUE -> vsyncNanos
            else -> maxOf(vsyncNanos, lastVsync)
        }
        return (elapsedNanos + boundaryTolerance) * fps / 1_000_000_000L
    }

    fun pause() {
        lastVsync = Long.MIN_VALUE
    }

    fun restart() {
        elapsedNanos = 0
        lastVsync = Long.MIN_VALUE
    }

    /** Repositions the timeline to [frame] without advancing it. / 跳转到指定帧。 */
    fun seekTo(frame: Long) {
        elapsedNanos = frame.coerceAtLeast(0L) * 1_000_000_000L / fps
        lastVsync = Long.MIN_VALUE
    }

    fun nextDeadline(vsyncNanos: Long, frame: Long): Long = vsyncNanos +
        ((frame + 1) * 1_000_000_000L / fps - elapsedNanos - boundaryTolerance).coerceAtLeast(0)
}

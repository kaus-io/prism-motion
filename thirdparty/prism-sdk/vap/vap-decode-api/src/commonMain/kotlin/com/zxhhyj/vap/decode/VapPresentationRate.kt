package com.zxhhyj.vap.decode

/** Lower presentation rates drop frames instead of slowing the source timeline. */
public object VapPresentationRate {
    public fun timelineFps(sourceFps: Int, requestedFps: Int): Int =
        if (requestedFps in 1 until sourceFps) sourceFps else requestedFps.coerceAtLeast(0)

    /** Nearest presentation bucket; integer arithmetic tolerates rounded codec PTS. */
    public fun bucket(elapsedUs: Long, fps: Int): Long {
        require(fps > 0)
        val time = elapsedUs.coerceAtLeast(0L)
        return time / 1_000_000L * fps +
            (time % 1_000_000L * fps + 500_000L) / 1_000_000L
    }
}

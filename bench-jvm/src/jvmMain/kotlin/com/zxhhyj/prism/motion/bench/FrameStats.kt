package com.zxhhyj.prism.motion.bench

/** 一帧间隔序列的统计。 / Statistics over a sequence of frame intervals. */
class FrameStats(samples: List<Long>) {
    val frames: Int = samples.size
    private val sorted = samples.sorted()

    val avgMs: Double = if (frames == 0) 0.0 else sorted.average() / 1000.0
    val fps: Double = if (avgMs <= 0) 0.0 else 1000.0 / avgMs
    val p95Ms: Double = percentile(95)
    val p99Ms: Double = percentile(99)
    val maxMs: Double = if (frames == 0) 0.0 else sorted.last() / 1000.0
    val jankRatio: Double = if (frames == 0) 0.0 else sorted.count { it > 25_000 } / frames.toDouble()
    val fpsStd: Double = if (frames == 0) 0.0 else {
        val mean = avgMs
        Math.sqrt(sorted.sumOf { (it / 1000.0 - mean) * (it / 1000.0 - mean) } / frames)
    }

    private fun percentile(p: Int): Double =
        if (frames == 0) 0.0 else sorted[(frames * p / 100).coerceAtMost(frames - 1)] / 1000.0
}

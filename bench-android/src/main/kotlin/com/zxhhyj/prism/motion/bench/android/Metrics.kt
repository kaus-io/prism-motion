package com.zxhhyj.prism.motion.bench.android

import kotlin.math.ceil
import kotlin.math.sqrt

/** UI 帧间隔统计，与动画成功提交帧率分开；阈值随显示刷新率变化。 / UI cadence statistics, separate from animation submissions, with a refresh-aware threshold. */
class FrameStats(samples: List<Long>, refreshRate: Double = 60.0) {
    val frames = samples.size
    private val sorted = samples.sorted()
    val avgMs = if (frames == 0) 0.0 else sorted.average() / 1000.0
    val fps = if (avgMs <= 0) 0.0 else 1000.0 / avgMs
    val p95Ms = percentile(0.95)
    val p99Ms = percentile(0.99)
    val maxMs = if (frames == 0) 0.0 else sorted.last() / 1000.0
    val jankRatio = if (frames == 0) 0.0 else sorted.count {
        it / 1000.0 > 1.5 * 1000.0 / refreshRate.coerceAtLeast(1.0)
    } / frames.toDouble()
    val frameStdMs = if (frames == 0) 0.0 else sqrt(sorted.sumOf {
        val delta = it / 1000.0 - avgMs
        delta * delta
    } / frames)

    private fun percentile(p: Double): Double = if (frames == 0) 0.0 else
        sorted[(ceil(frames * p).toInt() - 1).coerceIn(sorted.indices)] / 1000.0
}

package com.zxhhyj.prism.motion.bench.android

import com.zxhhyj.prism.motion.PrismMotionFrameObserver

/** 有界、无逐帧分配的回调记录器；工作线程写入，测量结束后读取。 / Bounded allocation-free callback recorder; workers write, results are read after measurement. */
internal class FrameTrace(capacity: Int = 8192) : PrismMotionFrameObserver {
    private val timestamps = LongArray(capacity)
    private var size = 0
    private var total = 0L
    private var last = Long.MIN_VALUE
    @Volatile var overflow = false
        private set
    @Volatile var invalidTimestamp = false
        private set

    init { require(capacity > 0) }

    @Synchronized
    override fun onFramePresented(timestampNs: Long) {
        if (timestampNs <= last) {
            invalidTimestamp = true
            return
        }
        last = timestampNs
        total++
        if (size == timestamps.size) overflow = true else timestamps[size++] = timestampNs
    }

    @Synchronized
    fun count(): Long = total

    /** 窗口左闭右开，不把预热或尾部回调算入 FPS。 / Uses a half-open window, excluding warmup and trailing callbacks. */
    @Synchronized
    fun window(startNs: Long, endNs: Long): LongArray {
        require(endNs >= startNs)
        var first = 0
        while (first < size && timestamps[first] < startNs) first++
        var end = first
        while (end < size && timestamps[end] < endNs) end++
        return timestamps.copyOfRange(first, end)
    }
}

/** 每个实例内部计算间隔，再汇总；绝不混用不同播放器的相邻时间戳。 / Computes intervals within each player, never across players. */
internal class PresentStats(val events: List<LongArray>, val seconds: Double, targetFps: Double) {
    val frames = events.sumOf { it.size }
    val perInstanceFps = events.map { if (seconds > 0) it.size / seconds else 0.0 }
    val fps = perInstanceFps.average().takeIf { it.isFinite() } ?: 0.0
    val minimumFps = perInstanceFps.minOrNull() ?: 0.0
    val intervals = FrameStats(events.flatMap { times ->
        (1 until times.size).map { index -> (times[index] - times[index - 1]) / 1000L }
    }, targetFps)
    val realtime = events.isEmpty() || minimumFps >= targetFps * 0.95
}

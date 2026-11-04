package com.zxhhyj.vap.decode

import kotlin.math.abs
import kotlin.math.round

/**
 * Wall-clock present grid. When [refreshHz] is known, the cell is quantized to
 * a whole number of vsyncs so 30fps lands on 2×60 / 3×90 instead of drifting
 * against the panel. Wake is scheduled [WAKE_LEAD_NS] early so a Handler tick
 * still catches the intended vsync. Choreographer is not used.
 *
 * 挂钟呈现格子。知道 [refreshHz] 时，格子量化成整数个 vsync，30fps 落在
 * 2×60 / 3×90 上，而不是跟面板慢慢错开。唤醒提前 [WAKE_LEAD_NS]，Handler
 * 仍能赶上目标 vsync。不使用 Choreographer。
 */
public object VapPresentGrid {
    public const val WAKE_LEAD_NS: Long = 1_500_000L
    private const val QUANTIZE_ERR_PERCENT: Long = 8L

    public fun cellPeriodNanos(fps: Int, refreshHz: Float): Long {
        if (fps <= 0) return 0L
        val raw = 1_000_000_000L / fps.toLong()
        if (refreshHz <= 1f) return raw
        val vsyncNs = 1_000_000_000.0 / refreshHz.toDouble()
        val n = round(raw.toDouble() / vsyncNs).toInt().coerceAtLeast(1)
        val quantized = round(n * vsyncNs).toLong().coerceAtLeast(1L)
        val err = abs(quantized - raw)
        return if (err * 100L <= raw * QUANTIZE_ERR_PERCENT) quantized else raw
    }

    public fun logicalIndex(elapsedNs: Long, cellPeriodNs: Long): Long {
        if (cellPeriodNs <= 0L) return 0L
        return elapsedNs.coerceAtLeast(0L) / cellPeriodNs
    }

    public fun dueNanos(startNs: Long, index: Long, cellPeriodNs: Long): Long =
        startNs + index * cellPeriodNs

    public fun isDue(nowNs: Long, dueNs: Long, leadNs: Long = WAKE_LEAD_NS): Boolean =
        nowNs + leadNs >= dueNs

    public fun delayMillisUntilDue(dueNs: Long, nowNs: Long, leadNs: Long = WAKE_LEAD_NS): Long {
        val delayNs = dueNs - leadNs - nowNs
        return if (delayNs <= 0L) 0L else (delayNs + 999_999L) / 1_000_000L
    }
}

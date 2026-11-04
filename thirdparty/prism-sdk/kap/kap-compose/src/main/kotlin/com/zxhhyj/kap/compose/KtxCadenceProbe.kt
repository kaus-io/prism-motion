package com.zxhhyj.kap.compose

/** Submission cadence, not scan-out. Enabled only by the debug experiment file. */
internal class KtxCadenceProbe(private val fps: Int) {
    private var previousTime = 0L
    private var previousFrame = -1L
    private var samples = 0
    private var total = 0L
    private var minimum = Long.MAX_VALUE
    private var maximum = 0L
    private var late = 0
    private var skipped = 0L
    private var unavailable = 0

    fun record(success: Boolean, frame: Long, timestamp: Long): String? {
        if (!success) {
            unavailable++
            return null
        }
        if (previousTime != 0L) {
            val delta = timestamp - previousTime
            samples++
            total += delta
            minimum = minOf(minimum, delta)
            maximum = maxOf(maximum, delta)
            if (delta > 1_500_000_000L / fps) late++
            skipped += (frame - previousFrame - 1).coerceAtLeast(0)
        }
        previousTime = timestamp
        previousFrame = frame
        if (samples < fps * 5) return null
        val result = "samples=$samples avg_us=${total / samples / 1000} " +
            "min_us=${minimum / 1000} max_us=${maximum / 1000} late=$late " +
            "skipped=$skipped unavailable=$unavailable"
        samples = 0
        total = 0
        minimum = Long.MAX_VALUE
        maximum = 0
        late = 0
        skipped = 0
        unavailable = 0
        return result
    }
}

package com.zxhhyj.prism.motion.bench

import java.io.File

/** 读 /proc/self/stat，算进程 CPU 占用。 / Reads /proc/self/stat to compute process CPU usage. */
object ProcStat {

    class Sample internal constructor(val ticks: Long, val nanos: Long)

    fun sample(): Sample = Sample(ticks(), System.nanoTime())

    fun percentBetween(start: Sample, end: Sample): Double {
        val elapsed = (end.nanos - start.nanos) / 1e9
        if (elapsed <= 0) return 0.0
        return ((end.ticks - start.ticks) / 100.0) / elapsed * 100.0
    }

    private fun ticks(): Long {
        val line = File("/proc/self/stat").readText()
        val fields = line.substringAfter(") ").split(' ')
        return (fields.getOrNull(11)?.toLongOrNull() ?: 0) + (fields.getOrNull(12)?.toLongOrNull() ?: 0)
    }
}

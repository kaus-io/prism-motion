package com.zxhhyj.prism.motion.bench.android

import android.os.Process

/** 使用内核累计进程 CPU 毫秒数，避免解析 proc 和硬编码 USER_HZ。 / Uses process CPU milliseconds without proc parsing or USER_HZ assumptions. */
object ProcStat {
    class Sample(val cpuMs: Long, val nanos: Long)

    fun sample(): Sample = Sample(Process.getElapsedCpuTime(), System.nanoTime())

    /** 100% 表示占满一个核；多线程可以超过 100%。 / 100% is one fully occupied core; multiple threads may exceed it. */
    fun percentBetween(start: Sample, end: Sample): Double {
        val elapsedMs = (end.nanos - start.nanos) / 1e6
        return if (elapsedMs <= 0) 0.0 else (end.cpuMs - start.cpuMs).coerceAtLeast(0) * 100.0 / elapsedMs
    }
}

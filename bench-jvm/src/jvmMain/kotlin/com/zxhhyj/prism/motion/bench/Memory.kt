package com.zxhhyj.prism.motion.bench

import java.io.File

/** 采样 JVM 进程内存：RSS 近似 PSS（桌面没有 PSS 概念）。 / Samples process RSS as a PSS stand-in. */
object Memory {

    fun rssMb(): Long = rssKb() / 1024

    fun rssKb(): Long {
        val status = File("/proc/self/status")
        if (!status.isFile) return 0
        for (line in status.readLines()) {
            if (line.startsWith("VmRSS:")) {
                return line.filter { it.isDigit() }.toLongOrNull() ?: 0
            }
        }
        return 0
    }

    fun heapMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    }
}

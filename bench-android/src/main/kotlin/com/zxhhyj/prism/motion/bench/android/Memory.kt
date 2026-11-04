package com.zxhhyj.prism.motion.bench.android

import android.os.Debug

/** 进程内存与 CPU 采样。 / Process memory and CPU sampling. */
object Memory {

    fun pssMb(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss / 1024L
    }
}

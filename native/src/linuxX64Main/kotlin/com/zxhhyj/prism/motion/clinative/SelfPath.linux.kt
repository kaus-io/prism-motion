package com.zxhhyj.prism.motion.clinative

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.readlink

@OptIn(ExperimentalForeignApi::class)
internal actual fun selfExecutablePath(): String? {
    val buffer = ByteArray(4096)
    val count = buffer.usePinned { pinned -> readlink("/proc/self/exe", pinned.addressOf(0), buffer.size.convert()) }
    if (count <= 0) return null
    return buffer.decodeToString(0, count.toInt())
}

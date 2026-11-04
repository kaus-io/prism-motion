package com.zxhhyj.prism.motion.probe

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.fstat
import platform.posix.open
import platform.posix.read
import platform.posix.stat

@OptIn(ExperimentalForeignApi::class)
internal actual object PlatformIo {

    actual fun size(path: String): Long = memScoped {
        val info = alloc<stat>()
        if (stat(path, info.ptr) != 0) error("stat 失败: $path")
        info.st_size
    }

    actual fun read(path: String): ByteArray {
        val fd = open(path, O_RDONLY)
        if (fd < 0) error("打开失败: $path")
        try {
            val info = memScoped { alloc<stat>().also { if (fstat(fd, it.ptr) != 0) error("fstat 失败: $path") } }
            val size = info.st_size.toInt()
            val buffer = ByteArray(size)
            var readTotal = 0
            buffer.usePinned { pinned ->
                while (readTotal < size) {
                    val n = read(fd, pinned.addressOf(readTotal), (size - readTotal).convert())
                    if (n <= 0L) break
                    readTotal += n.toInt()
                }
            }
            return if (readTotal == size) buffer else buffer.copyOf(readTotal)
        } finally {
            close(fd)
        }
    }
}

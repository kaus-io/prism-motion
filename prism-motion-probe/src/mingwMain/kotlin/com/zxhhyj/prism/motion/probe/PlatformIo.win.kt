package com.zxhhyj.prism.motion.probe

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.DWORDVar
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.GENERIC_READ
import platform.windows.GetFileSizeEx
import platform.windows.LARGE_INTEGER
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.OPEN_EXISTING
import platform.windows.ReadFile

@OptIn(ExperimentalForeignApi::class)
internal actual object PlatformIo {

    actual fun size(path: String): Long {
        val handle = CreateFileW(
            path, GENERIC_READ.toUInt(), FILE_SHARE_READ.toUInt() or FILE_SHARE_WRITE.toUInt(),
            null, OPEN_EXISTING.toUInt(), FILE_ATTRIBUTE_NORMAL.toUInt(), null,
        )
        if (handle == INVALID_HANDLE_VALUE || handle == null) error("stat 失败: $path")
        try {
            return memScoped {
                val size = alloc<LARGE_INTEGER>()
                if (GetFileSizeEx(handle, size.ptr) == 0) error("stat 失败: $path")
                size.QuadPart
            }
        } finally {
            CloseHandle(handle)
        }
    }

    actual fun read(path: String): ByteArray {
        val handle = CreateFileW(
            path, GENERIC_READ.toUInt(), FILE_SHARE_READ.toUInt() or FILE_SHARE_WRITE.toUInt(),
            null, OPEN_EXISTING.toUInt(), FILE_ATTRIBUTE_NORMAL.toUInt(), null,
        )
        if (handle == INVALID_HANDLE_VALUE || handle == null) error("打开失败: $path")
        try {
            val chunks = ArrayList<ByteArray>()
            val buffer = ByteArray(1 shl 16)
            memScoped {
                val read = alloc<DWORDVar>()
                while (true) {
                    val ok = buffer.usePinned { pinned ->
                        ReadFile(handle, pinned.addressOf(0), buffer.size.convert(), read.ptr, null)
                    }
                    if (ok == 0) break
                    val count = read.ptr[0].toInt()
                    if (count <= 0) break
                    chunks += buffer.copyOf(count)
                }
            }
            val total = chunks.sumOf { it.size }
            val out = ByteArray(total)
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(out, offset)
                offset += chunk.size
            }
            return out
        } finally {
            CloseHandle(handle)
        }
    }
}

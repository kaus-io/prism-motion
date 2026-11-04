package com.zxhhyj.prism.motion.clinative

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import stb.stbi_image_free
import stb.stbi_load_from_memory

@OptIn(ExperimentalForeignApi::class)
internal actual object ImageDecoder {

    actual fun decodePng(bytes: ByteArray): Frame {
        require(bytes.isNotEmpty()) { "空文件" }
        return memScoped {
            val width = alloc<IntVar>()
            val height = alloc<IntVar>()
            val channels = alloc<IntVar>()
            val raw = bytes.usePinned { pinned ->
                stbi_load_from_memory(
                    pinned.addressOf(0).reinterpret(),
                    bytes.size,
                    width.ptr,
                    height.ptr,
                    channels.ptr,
                    4,
                )
            }
            val data = raw ?: error("PNG 解码失败")
            try {
                val w = width.ptr[0]
                val h = height.ptr[0]
                val pixels = ByteArray(w * h * 4)
                pixels.usePinned { pinned -> memcpy(pinned.addressOf(0), data, (w * h * 4).convert()) }
                Frame(w, h, pixels)
            } finally {
                stbi_image_free(data)
            }
        }
    }
}

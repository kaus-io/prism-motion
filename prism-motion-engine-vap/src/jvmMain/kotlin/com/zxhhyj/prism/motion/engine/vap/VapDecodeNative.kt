package com.zxhhyj.prism.motion.engine.vap

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.zxhhyj.prism.motion.utils.JvmNativeLoader

/**
 * 通过 JNA 调用 `libprismdecode.so`（OpenH264 解码器 + 我们的 C 封装），
 * 完全不依赖 FFmpeg。 / Calls the OpenH264 decode shim via JNA; no FFmpeg involved.
 */
internal object VapDecodeNative {

    interface Lib : Library {
        fun prism_dec_create(): Pointer?
        fun prism_dec_destroy(decoder: Pointer?)
        fun prism_dec_decode(decoder: Pointer?, data: Pointer, size: Int, out: Pointer, capacity: Int): Int
        fun prism_dec_width(decoder: Pointer?): Int
        fun prism_dec_height(decoder: Pointer?): Int
    }

    private val lib: Lib? by lazy { runCatching { Native.load(resolveLibrary(), Lib::class.java) }.getOrNull() }

    val available: Boolean get() = lib != null

    /** 解码一组 Annex-B 采样，返回紧凑 I420 帧列表。 / Decodes Annex-B samples into tight I420 frames. */
    fun decodeAll(samples: List<ByteArray>, width: Int, height: Int): List<ByteArray> {
        val library = lib ?: error("缺少 libprismdecode（先跑 scripts/build-thirdparty.sh）")
        val decoder = library.prism_dec_create() ?: error("OpenH264 解码器初始化失败")
        try {
            val capacity = width * height * 3 / 2
            val buffer = Memory(capacity.toLong())
            val frames = ArrayList<ByteArray>(samples.size)
            for (sample in samples) {
                val input = Memory(sample.size.toLong())
                input.write(0, sample, 0, sample.size)
                val ret = library.prism_dec_decode(decoder, input, sample.size, buffer, capacity)
                if (ret == 1) frames += buffer.getByteArray(0, capacity)
            }
            return frames
        } finally {
            library.prism_dec_destroy(decoder)
        }
    }

    private fun resolveLibrary(): String =
        JvmNativeLoader.resolve("prismdecode", "PRISM_MOTION_DECODE_LIB")
}

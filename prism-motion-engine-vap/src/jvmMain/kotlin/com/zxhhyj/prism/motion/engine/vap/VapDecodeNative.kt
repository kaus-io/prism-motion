package com.zxhhyj.prism.motion.engine.vap

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.zxhhyj.prism.motion.utils.JvmNativeLoader

/** 通过 JNA 调用 OpenH264 C 封装，不依赖 FFmpeg。 / Calls the OpenH264 C shim via JNA without FFmpeg. */
internal object VapDecodeNative {
    interface Lib : Library {
        fun prism_dec_create(): Pointer?
        fun prism_dec_destroy(decoder: Pointer?)
        fun prism_dec_decode(decoder: Pointer?, data: Pointer, size: Int, out: Pointer, capacity: Int): Int
        fun prism_dec_width(decoder: Pointer?): Int
        fun prism_dec_height(decoder: Pointer?): Int
    }

    private val loadResult: Result<Lib> by lazy { runCatching { Native.load(resolveLibrary(), Lib::class.java) } }
    val available: Boolean get() = loadResult.isSuccess

    /**
     * 解码 Annex-B 采样，返回独立的紧凑 I420 帧；library 参数用于无原生依赖的 ABI 测试。
     * Decodes Annex-B samples into independent tight I420 frames; library permits native-free ABI tests.
     */
    fun decodeAll(samples: List<ByteArray>, width: Int, height: Int, library: Lib? = null): List<ByteArray> {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "OpenH264 requires positive even I420 dimensions: ${width}x$height"
        }
        val pixels = width.toLong() * height
        val capacityLong = pixels + pixels / 2
        require(capacityLong <= Int.MAX_VALUE) { "I420 frame is too large: ${width}x$height" }
        require(samples.isNotEmpty()) { "No Annex-B samples to decode" }
        samples.forEachIndexed { index, sample ->
            require(sample.isNotEmpty()) { "Empty Annex-B sample at index $index" }
        }
        val capacity = capacityLong.toInt()
        val native = library ?: loadResult.getOrElse {
            throw IllegalStateException("Cannot load OpenH264 shim libprismdecode; set PRISM_MOTION_DECODE_LIB", it)
        }
        val decoder = native.prism_dec_create() ?: error("prism_dec_create failed: OpenH264 decoder initialization returned null")
        try {
            // 每次动画只分配一对缓冲，且在销毁解码器前释放。 / Allocate one input/output pair per animation and close it deterministically.
            return Memory(samples.maxOf { it.size }.toLong()).use { input ->
                Memory(capacityLong).use { output ->
                    val frames = ArrayList<ByteArray>(samples.size)
                    for ((index, sample) in samples.withIndex()) {
                        input.write(0, sample, 0, sample.size)
                        when (val status = native.prism_dec_decode(decoder, input, sample.size, output, capacity)) {
                            0 -> Unit // 参数集等可能不出帧。 / Parameter sets and buffered input need not produce a frame.
                            1 -> {
                                val decodedWidth = native.prism_dec_width(decoder)
                                val decodedHeight = native.prism_dec_height(decoder)
                                check(decodedWidth == width && decodedHeight == height) {
                                    "OpenH264 sample $index produced ${decodedWidth}x$decodedHeight; expected ${width}x$height"
                                }
                                frames += output.getByteArray(0, capacity)
                            }
                            else -> error(
                                "prism_dec_decode failed at sample $index (${sample.size} bytes): status=$status, " +
                                    "expected=${width}x$height, I420 capacity=$capacity" +
                                    if (status == -2) " (output buffer too small)" else "",
                            )
                        }
                    }
                    check(frames.isNotEmpty()) {
                        "OpenH264 produced no frames from ${samples.size} Annex-B samples; " +
                            "the shim reported only need-more-data (it cannot expose detailed decoder errors or flush delayed frames)"
                    }
                    frames
                }
            }
        } finally {
            native.prism_dec_destroy(decoder)
        }
    }

    private fun resolveLibrary(): String = JvmNativeLoader.resolve("prismdecode", "PRISM_MOTION_DECODE_LIB")
}

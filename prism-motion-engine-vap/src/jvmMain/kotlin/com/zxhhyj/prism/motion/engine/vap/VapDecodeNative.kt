package com.zxhhyj.prism.motion.engine.vap

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

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
        val library = lib ?: error("缺少 libprismdecode（先跑 thirdparty/build-thirdparty.sh）")
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

    private fun resolveLibrary(): String {
        System.getenv("PRISM_MOTION_DECODE_LIB")?.takeIf { it.isNotBlank() }?.let { return it }
        val name = System.mapLibraryName("prismdecode")
        val cached = File(System.getProperty("user.home"), ".cache/prism-motion/native/$name")
        if (cached.isFile) return cached.absolutePath
        val local = File("thirdparty/native/${platform()}/$name")
        if (local.isFile) return local.absolutePath
        return "prismdecode"
    }

    private fun platform(): String {
        val os = System.getProperty("os.name").lowercase()
        val name = when {
            os.contains("linux") -> "linux"
            os.contains("mac") -> "macos"
            os.contains("windows") -> "windows"
            else -> os.replace(' ', '-')
        }
        val arch = System.getProperty("os.arch").lowercase()
        return "$name-" + when (arch) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> arch
        }
    }
}

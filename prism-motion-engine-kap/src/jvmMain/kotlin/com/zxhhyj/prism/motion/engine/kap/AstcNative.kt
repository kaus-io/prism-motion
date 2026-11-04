package com.zxhhyj.prism.motion.engine.kap

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.zxhhyj.prism.motion.utils.JvmNativeLoader

/**
 * 通过 JNA 直接调用 astcenc 的 C 接口（`libastcenc.so`），比逐帧起子进程快得多，
 * 也不再需要临时文件。 / Calls astcenc's C API via JNA: no per-frame subprocess or temp files.
 *
 * 库查找顺序 / lookup order：`PRISM_MOTION_ASTC_LIB` → `~/.cache/prism-motion/native/` →
 * `thirdparty/native/<platform>/`（相对工作目录）→ 系统动态库路径。
 */
internal object AstcNative {

    private const val ASTCENC_PRF_LDR = 0
    private const val ASTCENC_TYPE_U8 = 0
    private const val ASTCENC_FLG_DECOMPRESS_ONLY = 1 shl 4

    interface Lib : Library {
        fun astcenc_config_init(
            profile: Int,
            blockX: Int,
            blockY: Int,
            blockZ: Int,
            quality: Float,
            flags: Int,
            config: Pointer,
        ): Int

        fun astcenc_context_alloc(config: Pointer, threadCount: Int, context: Pointer): Int
        fun astcenc_decompress_image(
            context: Pointer,
            data: Pointer,
            dataSize: Long,
            image: Pointer,
            swizzle: Pointer,
            flags: Int,
        ): Int

        fun astcenc_context_free(context: Pointer)
    }

    @Structure.FieldOrder("dimX", "dimY", "dimZ", "dataType", "data")
    class AstcImage : Structure() {
        @JvmField var dimX: Int = 0
        @JvmField var dimY: Int = 0
        @JvmField var dimZ: Int = 0
        @JvmField var dataType: Int = 0
        @JvmField var data: Pointer? = null
    }

    private val lib: Lib? by lazy { runCatching { Native.load(resolveLibrary(), Lib::class.java) }.getOrNull() }

    val available: Boolean get() = lib != null

    /** 解码一帧 ASTC 块到 RGBA。 / Decodes one ASTC payload into RGBA. */
    fun decode(blocks: ByteArray, width: Int, height: Int, blockWidth: Int, blockHeight: Int): ByteArray? {
        val library = lib ?: return null
        val config = Memory(1024)
        val quality = 0.0f // ASTCENC_PRE_MEDIUM
        val configResult = library.astcenc_config_init(ASTCENC_PRF_LDR, blockWidth, blockHeight, 1, quality, ASTCENC_FLG_DECOMPRESS_ONLY, config)
        if (configResult != 0) return null

        val contextRef = Memory(Native.POINTER_SIZE.toLong())
        val allocResult = library.astcenc_context_alloc(config, 1, contextRef)
        if (allocResult != 0) return null
        val context = contextRef.getPointer(0)

        val rgba = Memory((width * height * 4).toLong())
        val rows = Memory(height.toLong() * Native.POINTER_SIZE)
        for (y in 0 until height) {
            rows.setPointer(y.toLong() * Native.POINTER_SIZE, rgba.share(y.toLong() * width * 4))
        }
        val image = AstcImage().apply {
            dimX = width
            dimY = height
            dimZ = 1
            dataType = ASTCENC_TYPE_U8
            data = rows
            write()
        }
        // astcenc_swizzle 是 4 个 int（enum），不是 4 个 byte。
        val swizzle = Memory(16)
        swizzle.setInt(0, 0) // ASTCENC_SWZ_R
        swizzle.setInt(4, 1) // G
        swizzle.setInt(8, 2) // B
        swizzle.setInt(12, 3) // A

        val source = Memory(blocks.size.toLong())
        source.write(0, blocks, 0, blocks.size)

        return try {
            val result = library.astcenc_decompress_image(context, source, blocks.size.toLong(), image.pointer, swizzle, 0)
            if (result != 0) null else rgba.getByteArray(0, width * height * 4)
        } finally {
            library.astcenc_context_free(context)
        }
    }

    private fun resolveLibrary(): String =
        JvmNativeLoader.resolve("astcenc", "PRISM_MOTION_ASTC_LIB")
}

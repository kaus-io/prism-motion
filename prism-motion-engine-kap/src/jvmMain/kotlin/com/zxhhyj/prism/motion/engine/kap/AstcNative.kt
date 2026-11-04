package com.zxhhyj.prism.motion.engine.kap

import com.sun.jna.IntegerType
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.zxhhyj.prism.motion.utils.JvmNativeLoader

/** 直接调用 astcenc 的公开 C ABI。 / Calls astcenc's public, non-diagnostic C ABI directly. */
internal object AstcNative {
    private const val ASTCENC_PRF_LDR_SRGB = 0
    private const val ASTCENC_PRF_LDR = 1
    private const val ASTCENC_TYPE_U8 = 0
    private const val ASTCENC_FLG_DECOMPRESS_ONLY = 1 shl 4

    /** size_t 的宽度取决于平台，不能固定为 Long。 / size_t is platform-sized, not always a Long. */
    class SizeT(value: Long = 0) : IntegerType(Native.SIZE_T_SIZE, value, true) {
        override fun toByte(): Byte = toLong().toByte()
        override fun toShort(): Short = toLong().toShort()
    }

    interface Lib : Library {
        fun astcenc_config_init(
            profile: Int,
            blockX: Int,
            blockY: Int,
            blockZ: Int,
            quality: Float,
            flags: Int,
            config: AstcConfig,
        ): Int

        fun astcenc_context_alloc(config: AstcConfig, threadCount: Int, context: Pointer): Int
        fun astcenc_decompress_image(
            context: Pointer,
            data: Pointer,
            dataSize: SizeT,
            image: AstcImage,
            swizzle: AstcSwizzle,
            threadIndex: Int,
        ): Int

        fun astcenc_context_free(context: Pointer)
        fun astcenc_get_error_string(status: Int): String?
    }

    /** 显式释放 JNA 结构的自有内存。 / Deterministically releases a structure's owned JNA memory. */
    abstract class OwnedStructure : Structure(), AutoCloseable {
        override fun close() {
            (pointer as Memory).close()
        }
    }

    /** 对应仓库内 astcenc.h，保留所有字段及原生对齐。 / Matches the vendored astcenc.h, including alignment. */
    @Structure.FieldOrder(
        "profile", "flags", "blockX", "blockY", "blockZ",
        "cwRWeight", "cwGWeight", "cwBWeight", "cwAWeight", "aScaleRadius", "rgbmMScale",
        "tunePartitionCountLimit", "tune2PartitionIndexLimit", "tune3PartitionIndexLimit", "tune4PartitionIndexLimit",
        "tuneBlockModeLimit", "tuneRefinementLimit", "tuneCandidateLimit",
        "tune2PartitioningCandidateLimit", "tune3PartitioningCandidateLimit", "tune4PartitioningCandidateLimit",
        "tuneDbLimit", "tuneMseOvershoot", "tune2PartitionEarlyOutLimitFactor", "tune3PartitionEarlyOutLimitFactor",
        "tune2PlaneEarlyOutLimitCorrelation", "tuneSearchMode0Enable", "progressCallback",
    )
    class AstcConfig : OwnedStructure() {
        @JvmField var profile: Int = 0
        @JvmField var flags: Int = 0
        @JvmField var blockX: Int = 0
        @JvmField var blockY: Int = 0
        @JvmField var blockZ: Int = 0
        @JvmField var cwRWeight: Float = 0f
        @JvmField var cwGWeight: Float = 0f
        @JvmField var cwBWeight: Float = 0f
        @JvmField var cwAWeight: Float = 0f
        @JvmField var aScaleRadius: Int = 0
        @JvmField var rgbmMScale: Float = 0f
        @JvmField var tunePartitionCountLimit: Int = 0
        @JvmField var tune2PartitionIndexLimit: Int = 0
        @JvmField var tune3PartitionIndexLimit: Int = 0
        @JvmField var tune4PartitionIndexLimit: Int = 0
        @JvmField var tuneBlockModeLimit: Int = 0
        @JvmField var tuneRefinementLimit: Int = 0
        @JvmField var tuneCandidateLimit: Int = 0
        @JvmField var tune2PartitioningCandidateLimit: Int = 0
        @JvmField var tune3PartitioningCandidateLimit: Int = 0
        @JvmField var tune4PartitioningCandidateLimit: Int = 0
        @JvmField var tuneDbLimit: Float = 0f
        @JvmField var tuneMseOvershoot: Float = 0f
        @JvmField var tune2PartitionEarlyOutLimitFactor: Float = 0f
        @JvmField var tune3PartitionEarlyOutLimitFactor: Float = 0f
        @JvmField var tune2PlaneEarlyOutLimitCorrelation: Float = 0f
        @JvmField var tuneSearchMode0Enable: Float = 0f
        @JvmField var progressCallback: Pointer? = null
    }

    @Structure.FieldOrder("dimX", "dimY", "dimZ", "dataType", "data")
    class AstcImage : OwnedStructure() {
        @JvmField var dimX: Int = 0
        @JvmField var dimY: Int = 0
        @JvmField var dimZ: Int = 0
        @JvmField var dataType: Int = 0
        @JvmField var data: Pointer? = null
    }

    /** swizzle 是四个 C enum，而不是字节。 / A swizzle consists of four C enums, not bytes. */
    @Structure.FieldOrder("r", "g", "b", "a")
    class AstcSwizzle : OwnedStructure() {
        @JvmField var r: Int = 0
        @JvmField var g: Int = 1
        @JvmField var b: Int = 2
        @JvmField var a: Int = 3
    }

    internal data class Layout(
        val width: Int,
        val height: Int,
        val blockWidth: Int,
        val blockHeight: Int,
        val srgb: Boolean = false,
    ) {
        val rgbaSize: Int
        val blockBytes: Int

        init {
            require(width in 1..0xFFFFFF && height in 1..0xFFFFFF) { "Invalid ASTC dimensions: ${width}x$height" }
            require(blockWidth to blockHeight in BLOCK_SIZES) { "Invalid ASTC footprint: ${blockWidth}x$blockHeight" }
            val rgbaBytes = width.toLong() * height * 4
            val compressedBytes = ((width.toLong() + blockWidth - 1) / blockWidth) *
                ((height.toLong() + blockHeight - 1) / blockHeight) * 16
            require(rgbaBytes <= Int.MAX_VALUE && compressedBytes <= Int.MAX_VALUE) { "ASTC image is too large: ${width}x$height" }
            rgbaSize = rgbaBytes.toInt()
            blockBytes = compressedBytes.toInt()
        }

        fun validate(blocks: ByteArray) {
            require(blocks.size == blockBytes) { "ASTC payload size ${blocks.size}; expected $blockBytes for ${width}x$height" }
        }
    }

    private val BLOCK_SIZES = setOf(
        4 to 4, 5 to 4, 5 to 5, 6 to 5, 6 to 6, 8 to 5, 8 to 6,
        8 to 8, 10 to 5, 10 to 6, 10 to 8, 10 to 10, 12 to 10, 12 to 12,
    )
    private val loadResult: Result<Lib> by lazy { runCatching { Native.load(resolveLibrary(), Lib::class.java) } }
    val available: Boolean get() = loadResult.isSuccess
    val loadFailure: Throwable? get() = loadResult.exceptionOrNull()

    // 调用方逐帧传入，最多缓存一个工作区；换尺寸或退出时释放。 / Per-frame callers reuse one bounded workspace.
    private var cached: Decoder? = null
    private val cleanupHook by lazy {
        Runtime.getRuntime().addShutdownHook(Thread({ clearCache() }, "prism-astc-cleanup"))
    }

    /** 缺库才返回 null，原生解码失败必须报错。 / Only a missing library returns null; native errors propagate. */
    @Synchronized
    fun decode(
        blocks: ByteArray,
        width: Int,
        height: Int,
        blockWidth: Int,
        blockHeight: Int,
        srgb: Boolean = false,
    ): ByteArray? {
        val layout = Layout(width, height, blockWidth, blockHeight, srgb)
        layout.validate(blocks)
        val library = loadResult.getOrNull() ?: return null
        cleanupHook
        if (cached?.layout != layout) {
            clearCache()
            cached = Decoder.create(library, layout)
        }
        return try {
            cached!!.decode(blocks)
        } catch (failure: Throwable) {
            try {
                clearCache()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    @Synchronized
    internal fun clearCache() {
        val previous = cached
        cached = null
        previous?.close()
    }

    /** 上下文与所有间接指针保持同寿命，单线程上下文可连续解码。 / Owns every indirect pointer and a reusable single-thread context. */
    internal class Decoder private constructor(
        private val library: Lib,
        val layout: Layout,
        private val context: Pointer,
        private val source: Memory,
        private val rgba: Memory,
        private val image: AstcImage,
        private val swizzle: AstcSwizzle,
        private val resources: List<AutoCloseable>,
    ) : AutoCloseable {
        private var closed = false

        @Synchronized
        fun decode(blocks: ByteArray): ByteArray {
            check(!closed) { "ASTC decoder is closed" }
            layout.validate(blocks)
            source.write(0, blocks, 0, blocks.size)
            checkStatus(library, "astcenc_decompress_image", library.astcenc_decompress_image(
                context, source, SizeT(blocks.size.toLong()), image, swizzle, 0,
            ))
            return rgba.getByteArray(0, layout.rgbaSize)
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            closeResources(resources)
        }

        companion object {
            fun create(library: Lib, layout: Layout): Decoder {
                val resources = ArrayList<AutoCloseable>()
                fun <T : AutoCloseable> own(resource: T): T = resource.also { resources += it }
                try {
                    val context = AstcConfig().use { config ->
                        checkStatus(library, "astcenc_config_init", library.astcenc_config_init(
                            if (layout.srgb) ASTCENC_PRF_LDR_SRGB else ASTCENC_PRF_LDR,
                            layout.blockWidth, layout.blockHeight, 1, 0f, ASTCENC_FLG_DECOMPRESS_ONLY, config,
                        ))
                        Memory(Native.POINTER_SIZE.toLong()).use { reference ->
                            reference.clear()
                            val status = library.astcenc_context_alloc(config, 1, reference)
                            // 即使错误返回了指针，也纳入清理。 / Also own a pointer returned alongside an error.
                            val allocated = reference.getPointer(0)
                            if (allocated != null) own(AutoCloseable { library.astcenc_context_free(allocated) })
                            checkStatus(library, "astcenc_context_alloc", status)
                            checkNotNull(allocated) { "astcenc_context_alloc succeeded with a null context" }
                        }
                    }
                    val source = own(Memory(layout.blockBytes.toLong()))
                    val rgba = own(Memory(layout.rgbaSize.toLong()))
                    // data 是深度切片数组；2D 图像只有一个连续切片，不是逐行指针。 / data is one contiguous 2D slice, not row pointers.
                    val slices = own(Memory(Native.POINTER_SIZE.toLong())).apply { setPointer(0, rgba) }
                    val image = own(AstcImage()).apply {
                        dimX = layout.width
                        dimY = layout.height
                        dimZ = 1
                        dataType = ASTCENC_TYPE_U8
                        data = slices
                        write()
                    }
                    val swizzle = own(AstcSwizzle()).apply { write() }
                    return Decoder(library, layout, context, source, rgba, image, swizzle, resources)
                } catch (failure: Throwable) {
                    try {
                        closeResources(resources)
                    } catch (cleanupFailure: Throwable) {
                        failure.addSuppressed(cleanupFailure)
                    }
                    throw failure
                }
            }
        }
    }

    private fun checkStatus(library: Lib, operation: String, status: Int) {
        check(status == 0) { "$operation failed ($status): ${library.astcenc_get_error_string(status) ?: "unknown ASTC error"}" }
    }

    private fun closeResources(resources: List<AutoCloseable>) {
        var failure: Throwable? = null
        for (resource in resources.asReversed()) {
            try {
                resource.close()
            } catch (next: Throwable) {
                if (failure == null) failure = next else failure.addSuppressed(next)
            }
        }
        failure?.let { throw it }
    }

    private fun resolveLibrary(): String = JvmNativeLoader.resolve("astcenc", "PRISM_MOTION_ASTC_LIB")
}

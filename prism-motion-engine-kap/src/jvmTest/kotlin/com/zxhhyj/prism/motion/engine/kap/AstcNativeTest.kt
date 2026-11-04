package com.zxhhyj.prism.motion.engine.kap

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AstcNativeTest {
    @Test
    fun mapsPublicAbiAndPlatformSizedLength() {
        AstcNative.AstcConfig().use { config ->
            assertEquals(if (Native.POINTER_SIZE == 8) 120 else 112, config.size())
            config.progressCallback = Pointer.createConstant(123)
            config.write()
            assertEquals(123L, Pointer.nativeValue(config.pointer.getPointer(if (Native.POINTER_SIZE == 8) 112 else 108)))
        }
        AstcNative.AstcImage().use { assertEquals(16 + Native.POINTER_SIZE, it.size()) }
        AstcNative.AstcSwizzle().use { swizzle ->
            assertEquals(16, swizzle.size())
            swizzle.write()
            assertContentEquals(intArrayOf(0, 1, 2, 3), swizzle.pointer.getIntArray(0, 4))
        }
        assertEquals(Native.SIZE_T_SIZE, Native.getNativeSize(AstcNative.SizeT::class.java))
    }

    @Test
    fun writesActualAstcDimensionsAndDepth() {
        val layout = AstcDecoder.layout(257, 9, 157)
        val blocks = ByteArray(layout.blockBytes)
        val file = AstcDecoder.astcFile(blocks, layout)
        assertContentEquals(byteArrayOf(0x13, 0xAB.toByte(), 0xA1.toByte(), 0x5C, 4, 4, 1), file.copyOfRange(0, 7))
        assertContentEquals(byteArrayOf(1, 1, 0, 9, 0, 0, 1, 0, 0), file.copyOfRange(7, 16))
        assertEquals(blocks.size + 16, file.size)
    }

    @Test
    fun missingFallbackToolReportsTheRequestedExecutable() {
        val error = assertFailsWith<IllegalStateException> {
            AstcDecoder.decodeWithTool(ByteArray(16), AstcNative.Layout(4, 4, 4, 4), "prism-nonexistent-astcenc-test-tool")
        }
        assertTrue(error.message.orEmpty().contains("cannot start astcenc tool 'prism-nonexistent-astcenc-test-tool'"))
        assertNotNull(error.cause)
    }

    /** 可选真实 CLI 回归，覆盖非整块尺寸及两种颜色配置。 / Optional real-CLI regression for edge dimensions and both profiles. */
    @Test
    fun fallbackToolPreservesDimensionsAndColorChannels() {
        val tool = System.getenv("PRISM_TEST_ASTCENC") ?: return
        val color = intArrayOf(255, 0, 255, 127)
        val blocks = List(6) { constantBlock(color) }.flatMap { it.asIterable() }.toByteArray()
        val expected = ByteArray(7 * 9 * 4) { color[it % 4].toByte() }
        for (srgb in listOf(false, true)) {
            assertContentEquals(expected, AstcDecoder.decodeWithTool(blocks, AstcNative.Layout(7, 9, 4, 4, srgb), tool))
        }
    }

    @Test
    fun distinguishesLinearAndSrgbVulkanFormats() {
        assertFalse(AstcDecoder.layout(4, 4, 157).srgb)
        assertTrue(AstcDecoder.layout(4, 4, 158).srgb)
        assertEquals(5, AstcDecoder.layout(5, 4, 159).blockWidth)
        assertEquals(4, AstcDecoder.layout(5, 4, 159).blockHeight)
        assertEquals(12, AstcDecoder.layout(12, 12, 184).blockWidth)
        assertFailsWith<IllegalArgumentException> { AstcDecoder.layout(4, 4, 156) }
    }

    @Test
    fun validatesDimensionsAndCompleteBlockPayloadBeforeNativeCall() {
        assertFailsWith<IllegalArgumentException> { AstcNative.decode(ByteArray(16), 0, 4, 4, 4) }
        assertFailsWith<IllegalArgumentException> { AstcNative.decode(ByteArray(16), Int.MAX_VALUE, 4, 4, 4) }
        assertFailsWith<IllegalArgumentException> { AstcNative.decode(ByteArray(16), 100_000, 100_000, 4, 4) }
        assertFailsWith<IllegalArgumentException> { AstcNative.decode(ByteArray(16), 4, 4, 3, 3) }
        assertFailsWith<IllegalArgumentException> { AstcDecoder.decode(ByteArray(16), 5, 4, 157) }
        assertFailsWith<IllegalArgumentException> { AstcDecoder.decode(ByteArray(33), 5, 4, 157) }
    }

    @Test
    fun reusesContextAndSliceBuffersAndClosesThem() {
        val library = FakeLib()
        val decoder = AstcNative.Decoder.create(library, AstcNative.Layout(5, 7, 4, 4))
        val blocks = ByteArray(64)
        val first = decoder.decode(blocks)
        val input = assertNotNull(library.input)
        val image = assertNotNull(library.imageMemory)
        val second = decoder.decode(blocks)
        assertEquals(1, library.allocations)
        assertEquals(2, library.decodes)
        assertEquals(1, library.profile)
        assertEquals(1, first.first().toInt())
        assertEquals(2, second.first().toInt())
        assertTrue(input.valid())
        decoder.close()
        decoder.close()
        assertEquals(1, library.frees)
        assertFalse(input.valid())
        assertFalse(image.valid())
        assertFailsWith<IllegalStateException> { decoder.decode(blocks) }
    }

    @Test
    fun reportsNativeStatusAndReleasesFailedAllocations() {
        val initFailure = FakeLib().apply { configStatus = 7 }
        val initError = assertFailsWith<IllegalStateException> {
            AstcNative.Decoder.create(initFailure, AstcNative.Layout(4, 4, 4, 4))
        }
        assertTrue(initError.message.orEmpty().contains("astcenc_config_init failed (7): fake-error"))
        assertEquals(0, initFailure.allocations)
        val allocFailure = FakeLib().apply { allocStatus = 2 }
        assertFailsWith<IllegalStateException> { AstcNative.Decoder.create(allocFailure, AstcNative.Layout(4, 4, 4, 4)) }
        assertEquals(1, allocFailure.frees)
        val nullContext = FakeLib().apply { returnNullContext = true }
        assertFailsWith<IllegalStateException> { AstcNative.Decoder.create(nullContext, AstcNative.Layout(4, 4, 4, 4)) }
        assertEquals(0, nullContext.frees)
        val decodeFailure = FakeLib().apply { decodeStatus = 9 }
        AstcNative.Decoder.create(decodeFailure, AstcNative.Layout(4, 4, 4, 4, srgb = true)).use { decoder ->
            assertEquals(0, decodeFailure.profile)
            val error = assertFailsWith<IllegalStateException> { decoder.decode(ByteArray(16)) }
            assertTrue(error.message.orEmpty().contains("astcenc_decompress_image failed (9)"))
        }
        assertEquals(1, decodeFailure.frees)
    }

    /** 有原生库时覆盖边缘块、重复解码和并发调用。 / With a native library, exercise edge blocks, reuse, and concurrency. */
    @Test
    fun decodesConstantBlocksWithRealLibraryAcrossThreads() {
        if (!AstcNative.available) {
            assertTrue(System.getenv("PRISM_MOTION_ASTC_LIB").isNullOrBlank(), "Explicit ASTC library failed to load: ${AstcNative.loadFailure}")
            return
        }
        val colors = listOf(
            intArrayOf(255, 0, 0, 255), intArrayOf(0, 255, 0, 127),
            intArrayOf(0, 0, 255, 255), intArrayOf(255, 255, 0, 255),
            intArrayOf(255, 0, 255, 0), intArrayOf(0, 255, 255, 255),
        )
        val blocks = colors.flatMap { constantBlock(it).asIterable() }.toByteArray()
        val expected = ByteArray(7 * 9 * 4)
        for (y in 0 until 9) for (x in 0 until 7) {
            val color = colors[(y / 4) * 2 + x / 4]
            for (channel in 0..3) expected[(y * 7 + x) * 4 + channel] = color[channel].toByte()
        }
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(16) { assertContentEquals(expected, assertNotNull(AstcNative.decode(blocks, 7, 9, 4, 4))) }
            val jobs = (0 until 32).map { index ->
                pool.submit(Callable {
                    val actual = assertNotNull(AstcNative.decode(blocks, 7, 9, 4, 4, srgb = index % 2 == 0))
                    assertContentEquals(expected, actual)
                })
            }
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            AstcNative.clearCache()
        }
    }

    // ASTC 规范的 UNORM16 常色空范围块，无需外部编码工具。 / A spec-defined UNORM16 constant-color void-extent block.
    private fun constantBlock(rgba: IntArray): ByteArray = ByteArray(16) { 0xFF.toByte() }.apply {
        this[0] = 0xFC.toByte()
        this[1] = 0xFD.toByte()
        rgba.forEachIndexed { channel, value ->
            val unorm16 = value * 257
            this[8 + channel * 2] = unorm16.toByte()
            this[9 + channel * 2] = (unorm16 ushr 8).toByte()
        }
    }

    private class FakeLib : AstcNative.Lib {
        var configStatus = 0
        var allocStatus = 0
        var decodeStatus = 0
        var returnNullContext = false
        var allocations = 0
        var frees = 0
        var decodes = 0
        var profile = -1
        var input: Memory? = null
        var imageMemory: Memory? = null
        private var context: Memory? = null

        override fun astcenc_config_init(profile: Int, blockX: Int, blockY: Int, blockZ: Int, quality: Float, flags: Int, config: AstcNative.AstcConfig): Int {
            this.profile = profile
            assertEquals(1, blockZ)
            assertEquals(1 shl 4, flags)
            config.profile = profile
            config.write()
            return configStatus
        }

        override fun astcenc_context_alloc(config: AstcNative.AstcConfig, threadCount: Int, context: Pointer): Int {
            allocations++
            assertEquals(1, threadCount)
            if (!returnNullContext) {
                this.context = Memory(1)
                context.setPointer(0, this.context)
            }
            return allocStatus
        }

        override fun astcenc_decompress_image(context: Pointer, data: Pointer, dataSize: AstcNative.SizeT, image: AstcNative.AstcImage, swizzle: AstcNative.AstcSwizzle, threadIndex: Int): Int {
            decodes++
            if (input != null) assertEquals(Pointer.nativeValue(input), Pointer.nativeValue(data))
            input = data as Memory
            imageMemory = image.pointer as Memory
            assertEquals(1, image.dimZ)
            assertEquals(0, image.dataType)
            assertEquals(0, threadIndex)
            assertEquals(dataSize.toLong(), data.size())
            val slices = image.data as Memory
            assertEquals(Native.POINTER_SIZE.toLong(), slices.size())
            assertContentEquals(intArrayOf(0, 1, 2, 3), swizzle.pointer.getIntArray(0, 4))
            slices.getPointer(0).setMemory(0, image.dimX.toLong() * image.dimY * 4, decodes.toByte())
            return decodeStatus
        }

        override fun astcenc_context_free(context: Pointer) {
            frees++
            this.context?.close()
        }

        override fun astcenc_get_error_string(status: Int): String = "fake-error"
    }
}

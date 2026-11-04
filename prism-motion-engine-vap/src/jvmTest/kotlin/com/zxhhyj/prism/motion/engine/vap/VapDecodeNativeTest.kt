package com.zxhhyj.prism.motion.engine.vap

import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VapDecodeNativeTest {
    @Test
    fun reusesInputOutputMemoryAndReturnsIndependentFrames() {
        val library = FakeLib(listOf(0, 1, 0, 1))
        val samples = listOf(byteArrayOf(1), byteArrayOf(2, 3, 4), byteArrayOf(5, 6), byteArrayOf(7))
        val frames = VapDecodeNative.decodeAll(samples, 4, 4, library)
        assertEquals(2, frames.size)
        assertContentEquals(ByteArray(24) { 2 }, frames[0])
        assertContentEquals(ByteArray(24) { 4 }, frames[1])
        assertEquals(samples.map { it.toList() }, library.inputs)
        assertEquals(1, library.creates)
        assertEquals(1, library.destroys)
        assertFalse(assertNotNull(library.input).valid())
        assertFalse(assertNotNull(library.output).valid())
    }

    @Test
    fun reportsNegativeAndUnexpectedStatusesAndAlwaysDestroysDecoder() {
        for (status in listOf(-1, -2, 2)) {
            val library = FakeLib(listOf(status))
            val error = assertFailsWith<IllegalStateException> {
                VapDecodeNative.decodeAll(listOf(byteArrayOf(1)), 4, 4, library)
            }
            assertTrue(error.message.orEmpty().contains("sample 0"))
            assertTrue(error.message.orEmpty().contains("status=$status"))
            if (status == -2) assertTrue(error.message.orEmpty().contains("output buffer too small"))
            assertEquals(1, library.destroys)
            assertFalse(assertNotNull(library.output).valid())
        }
    }

    @Test
    fun doesNotReturnStaleBytesForMismatchedDimensions() {
        val library = FakeLib(listOf(1)).apply { decodedWidth = 2; decodedHeight = 2 }
        val error = assertFailsWith<IllegalStateException> {
            VapDecodeNative.decodeAll(listOf(byteArrayOf(1)), 4, 4, library)
        }
        assertTrue(error.message.orEmpty().contains("produced 2x2; expected 4x4"))
        assertEquals(1, library.destroys)
    }

    @Test
    fun noOutputAndNullDecoderAreDeterministicFailures() {
        val library = FakeLib(listOf(0, 0))
        val error = assertFailsWith<IllegalStateException> {
            VapDecodeNative.decodeAll(listOf(byteArrayOf(1), byteArrayOf(2)), 4, 4, library)
        }
        assertTrue(error.message.orEmpty().contains("no frames from 2 Annex-B samples"))
        assertEquals(1, library.destroys)
        val nullLibrary = FakeLib(emptyList()).apply { nullDecoder = true }
        val initError = assertFailsWith<IllegalStateException> {
            VapDecodeNative.decodeAll(listOf(byteArrayOf(1)), 4, 4, nullLibrary)
        }
        assertTrue(initError.message.orEmpty().contains("prism_dec_create failed"))
        assertEquals(0, nullLibrary.destroys)
    }

    @Test
    fun validatesInputBeforeAllocatingNativeDecoder() {
        val library = FakeLib(emptyList())
        for ((width, height) in listOf(0 to 4, -2 to 4, 3 to 4, 4 to 3, 100_000 to 100_000)) {
            assertFailsWith<IllegalArgumentException> {
                VapDecodeNative.decodeAll(listOf(byteArrayOf(1)), width, height, library)
            }
        }
        assertFailsWith<IllegalArgumentException> { VapDecodeNative.decodeAll(emptyList(), 4, 4, library) }
        assertFailsWith<IllegalArgumentException> { VapDecodeNative.decodeAll(listOf(ByteArray(0)), 4, 4, library) }
        assertEquals(0, library.creates)
    }

    /** 纯 AUD NAL 合法但不能出帧，不应静默返回空列表。 / An AUD-only stream must fail explicitly, not return an empty list. */
    @Test
    fun realLibraryReportsNoFramesForAudOnlyStream() {
        if (!VapDecodeNative.available) {
            assertTrue(System.getenv("PRISM_MOTION_DECODE_LIB").isNullOrBlank(), "Explicit OpenH264 shim failed to load")
            return
        }
        val error = assertFailsWith<IllegalStateException> {
            VapDecodeNative.decodeAll(listOf(byteArrayOf(0, 0, 0, 1, 9, 0xF0.toByte())), 4, 4)
        }
        assertTrue(error.message.orEmpty().contains("no frames"))
    }

    private class FakeLib(private val statuses: List<Int>) : VapDecodeNative.Lib {
        var creates = 0
        var destroys = 0
        var decodedWidth = 4
        var decodedHeight = 4
        var nullDecoder = false
        var input: Memory? = null
        var output: Memory? = null
        val inputs = ArrayList<List<Byte>>()

        override fun prism_dec_create(): Pointer? {
            creates++
            return if (nullDecoder) null else Pointer.createConstant(1)
        }

        override fun prism_dec_destroy(decoder: Pointer?) {
            destroys++
        }

        override fun prism_dec_decode(decoder: Pointer?, data: Pointer, size: Int, out: Pointer, capacity: Int): Int {
            if (input != null) assertEquals(Pointer.nativeValue(input), Pointer.nativeValue(data))
            if (output != null) assertEquals(Pointer.nativeValue(output), Pointer.nativeValue(out))
            input = data as Memory
            output = out as Memory
            assertEquals(24, capacity)
            inputs += data.getByteArray(0, size).toList()
            val status = statuses[inputs.lastIndex]
            if (status == 1) out.setMemory(0, capacity.toLong(), inputs.size.toByte())
            return status
        }

        override fun prism_dec_width(decoder: Pointer?): Int = decodedWidth
        override fun prism_dec_height(decoder: Pointer?): Int = decodedHeight
    }
}

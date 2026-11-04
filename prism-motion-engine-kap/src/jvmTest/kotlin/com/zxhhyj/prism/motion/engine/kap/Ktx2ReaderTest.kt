package com.zxhhyj.prism.motion.engine.kap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Ktx2ReaderTest {

    @Test
    fun readsRgba8Animation() {
        val bytes = rgba8Ktx2(width = 2, height = 1, frames = 2, fps = 12)
        val animation = Ktx2Reader.read(bytes)

        assertEquals(2, animation.width)
        assertEquals(1, animation.height)
        assertEquals(12, animation.fps)
        assertEquals(2, animation.frames.size)

        val first = animation.frames[0].rgba
        assertEquals(10, first[0].toInt())
        assertEquals(20, first[1].toInt())
        assertEquals(30, first[2].toInt())
        assertEquals(255, first[3].toInt() and 0xFF)
    }

    @Test
    fun rejectsNonKtx2() {
        val error = runCatching { Ktx2Reader.read(ByteArray(256)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    /** 构造一个最小 RGBA8 KTX2：header + level index + kv + layer 数据。 */
    private fun rgba8Ktx2(width: Int, height: Int, frames: Int, fps: Int): ByteArray {
        val layerBytes = width * height * 4
        val kv = keyValues(mapOf("anim.fps" to fps.toString()))
        val kvdOffset = 104
        val levelOffset = align(kvdOffset + kv.size, 4)
        val levelLength = layerBytes * frames
        val out = ByteArray(levelOffset + levelLength)

        byteArrayOf(0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, 0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
            .copyInto(out, 0)
        putU32(out, 12, 37) // VK_FORMAT_R8G8B8A8_UNORM
        putU32(out, 16, 1)
        putU32(out, 20, width)
        putU32(out, 24, height)
        putU32(out, 32, frames)
        putU32(out, 36, 1)
        putU32(out, 40, 1)
        putU32(out, 56, kvdOffset)
        putU32(out, 60, kv.size)
        putU64(out, 80, levelOffset.toLong())
        putU64(out, 88, levelLength.toLong())
        putU64(out, 96, levelLength.toLong())
        kv.copyInto(out, kvdOffset)

        for (frame in 0 until frames) {
            val base = levelOffset + frame * layerBytes
            out[base] = (10 + frame).toByte()
            out[base + 1] = 20
            out[base + 2] = 30
            out[base + 3] = 255.toByte()
        }
        return out
    }

    private fun keyValues(values: Map<String, String>): ByteArray {
        val chunks = ArrayList<ByteArray>()
        for ((key, value) in values.toSortedMap()) {
            val entry = "$key\u0000$value".encodeToByteArray()
            val length = align(entry.size, 4)
            val chunk = ByteArray(4 + length)
            putU32(chunk, 0, length)
            entry.copyInto(chunk, 4)
            chunks += chunk
        }
        return chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
    }

    private fun align(value: Int, alignment: Int): Int = (value + alignment - 1) / alignment * alignment

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) target[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    private fun putU64(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) target[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }
}

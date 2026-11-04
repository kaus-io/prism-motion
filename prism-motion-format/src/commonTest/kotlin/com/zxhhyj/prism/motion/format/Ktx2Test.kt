package com.zxhhyj.prism.motion.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Ktx2Test {

    @Test
    fun readsRgba8Animation() {
        val bytes = rgba8(width = 2, height = 1, frames = 2, fps = 12)
        val animation = Ktx2Reader.read(bytes)

        assertEquals(2, animation.width)
        assertEquals(1, animation.height)
        assertEquals(12, animation.fps)
        assertEquals(2, animation.layers.size)
        assertEquals(Ktx2Format.RGBA8, animation.format)
        assertEquals(10, animation.layers[0][0].toInt())
    }

    @Test
    fun rejectsNonKtx2() {
        assertEquals(false, Ktx2Reader.isKtx2(ByteArray(256)))
        assertTrue(runCatching { Ktx2Reader.read(ByteArray(256)) }.isFailure)
    }

    @Test
    fun roundTripsThroughWriter() {
        val layers = listOf(ByteArray(2 * 1 * 4) { 7 }, ByteArray(2 * 1 * 4) { 9 })
        val bytes = Ktx2.write(Ktx2Format.RGBA8, 2, 1, layers, mapOf("anim.fps" to "24"))
        val animation = Ktx2Reader.read(bytes)
        assertEquals(24, animation.fps)
        assertEquals(2, animation.layers.size)
    }

    private fun rgba8(width: Int, height: Int, frames: Int, fps: Int): ByteArray =
        Ktx2.write(
            Ktx2Format.RGBA8,
            width,
            height,
            List(frames) { index -> ByteArray(width * height * 4) { (10 + index).toByte() } },
            mapOf("anim.fps" to fps.toString()),
        )
}

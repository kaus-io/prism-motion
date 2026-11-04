package com.zxhhyj.prism.motion.engine.vap

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VapDecodeTest {

    /** 设置环境变量后测试无延迟 VAP 文件。 / Opt in to a no-delay VAP fixture; requires libprismdecode. */
    @Test
    fun decodesVapWithoutFfmpeg() {
        val path = System.getenv("PRISM_TEST_VAP_MP4") ?: return
        val demuxed = VapMp4.demux(File(path).readBytes())

        assertTrue(demuxed.samples.isNotEmpty())
        assertTrue(demuxed.config.width > 0)

        val frames = VapDecodeNative.decodeAll(
            demuxed.samples,
            demuxed.config.videoWidth,
            demuxed.config.videoHeight,
        )
        assertEquals(demuxed.samples.size, frames.size)
        val frameBytes = demuxed.config.videoWidth * demuxed.config.videoHeight * 3 / 2
        frames.forEach { assertEquals(frameBytes, it.size) }

        val composed = VapFrames.compose(frames.first(), demuxed.config)
        assertEquals(demuxed.config.width, composed.width)
        assertEquals(demuxed.config.height, composed.height)
    }
}

package com.zxhhyj.prism.motion.engine.vap

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VapDecodeTest {

    /** 默认跳过；设置 PRISM_TEST_VAP_MP4 指向一个 VAP 文件时才真解码（需要 libprismdecode）。 */
    @Test
    fun decodesVapWithoutFfmpeg() {
        val path = System.getenv("PRISM_TEST_VAP_MP4") ?: return
        val demuxed = VapMp4.demux(File(path).readBytes())

        assertEquals(6, demuxed.samples.size)
        assertTrue(demuxed.config.width > 0)

        val frames = VapDecodeNative.decodeAll(
            demuxed.samples,
            demuxed.config.videoWidth,
            demuxed.config.videoHeight,
        )
        assertEquals(6, frames.size)

        val composed = VapFrames.compose(frames.first(), demuxed.config)
        assertEquals(demuxed.config.width, composed.width)
        assertEquals(demuxed.config.height, composed.height)
    }
}

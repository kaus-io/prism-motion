package com.zxhhyj.vap.player

import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class VapCompositionRegionLoadTest {
    @Test
    fun sourceAndSpecLoadersParseOnlyTheSelectedRegion() = runBlocking {
        val file = Files.createTempFile("vap-composition-region-", ".bin").toFile()
        try {
            val json = """{"info":{"v":3,"f":12,"w":2,"h":2,"videoW":4,"videoH":2,"fps":30,"aFrame":[0,0,2,2],"rgbFrame":[2,0,2,2]}}""".encodeToByteArray()
            val mp4 = ByteBuffer.allocate(8 + json.size).putInt(8 + json.size).put("vapc".encodeToByteArray()).put(json).array()
            val prefix = byteArrayOf(99, 98, 97)
            file.writeBytes(prefix + mp4 + byteArrayOf(96, 95))
            val region = VapSource.Region(VapSource.AbsolutePath(file.absolutePath), prefix.size.toLong(), mp4.size.toLong())
            val synchronous = loadVapComposition(region.toCompositionSpec(), prewarmGpu = false)
            val asynchronous = loadVapCompositionAsync(region, prewarmGpu = false)
            assertEquals(12, synchronous.config.totalFrames)
            assertEquals(synchronous.config, asynchronous.config)
            assertEquals(region, asynchronous.source)
        } finally {
            Files.deleteIfExists(file.toPath())
        }
    }
}

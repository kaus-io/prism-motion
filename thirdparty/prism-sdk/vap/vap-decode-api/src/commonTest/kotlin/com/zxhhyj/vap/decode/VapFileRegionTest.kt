package com.zxhhyj.vap.decode

import com.zxhhyj.vap.mp4.Mp4BoxParser
import com.zxhhyj.vap.player.VapSource
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class VapFileRegionTest {
    @Test
    fun positionalReadsExposeOnlyLogicalRegionAndDoNotChangeSeek() = withFixture(byteArrayOf(9, 8, 1, 2, 3, 7)) { file ->
        FileInputStream(file).channel.use { channel ->
            channel.position(5L)
            val source = VapFileChannelMp4Source(channel, 2L, 3L)
            val destination = ByteArray(5) { 6 }
            source.readAt(0L, destination, 1, 3)
            assertContentEquals(byteArrayOf(6, 1, 2, 3, 6), destination)
            assertEquals(3L, source.size())
            assertEquals(5L, channel.position())
            source.readAt(3L, destination, 0, 0)
            assertFailsWith<IllegalArgumentException> { source.readAt(2L, destination, 0, 2) }
            assertFailsWith<IllegalArgumentException> { source.readAt(-1L, destination, 0, 1) }
            assertFailsWith<IllegalArgumentException> { source.readAt(Long.MAX_VALUE, destination, 0, 1) }
            assertFailsWith<IllegalArgumentException> { source.readAt(0L, destination, 4, 2) }
        }
    }

    @Test
    fun validatesPhysicalBoundsAndCheckedArithmetic() = withFixture(ByteArray(8)) { file ->
        assertFailsWith<IllegalArgumentException> { openVapFileMp4Source(file.path, 7L, 2L) }
        assertFailsWith<IllegalArgumentException> { openVapFileMp4Source(file.path, 9L, 0L) }
        assertFailsWith<IllegalArgumentException> { openVapFileMp4Source(file.path, Long.MAX_VALUE, 1L) }
        requireVapRegionBounds(Long.MAX_VALUE - 1L, 1L, Long.MAX_VALUE)
        assertFailsWith<IllegalArgumentException> { requireVapRegionBounds(Long.MAX_VALUE - 1L, 2L, Long.MAX_VALUE) }
    }

    @Test
    fun independentOpeningsDoNotSharePositionsOrLifetimes() = withFixture(byteArrayOf(9, 1, 2, 3, 8)) { file ->
        openVapFileMp4Source(file.path, 1L, 3L).use { first ->
            val second = openVapFileMp4Source(file.path, 2L, 2L)
            val value = ByteArray(1)
            second.use { it.readAt(1L, value) }
            assertContentEquals(byteArrayOf(3), value)
            first.readAt(0L, value)
            assertContentEquals(byteArrayOf(1), value)
        }
    }

    @Test
    fun embeddedPosterAndToEofBoxesStopAtLogicalRegionEnd() {
        val poster = byteArrayOf(1, 2, 3)
        val inside = box("vapp", poster, toEof = true)
        withFixture(byteArrayOf(99, 98) + inside + box("vapp", byteArrayOf(4, 5))) { file ->
            openVapFileMp4Source(file.path, 2L, inside.size.toLong()).use { source ->
                assertContentEquals(poster, Mp4BoxParser.findPayload(source, "vapp"))
                assertEquals(inside.size.toLong(), Mp4BoxParser.parseTopLevel(source).single().size)
            }
        }
    }

    @Test
    fun metadataAndCacheIdentityAreBoundedToEachRegion() {
        val first = metadataBox(12)
        val second = metadataBox(24)
        val prefix = byteArrayOf(99, 98, 97)
        withFixture(prefix + first + second + byteArrayOf(96, 95)) { file ->
            val base = VapSource.AbsolutePath(file.absolutePath)
            val firstRegion = VapSource.Region(base, prefix.size.toLong(), first.size.toLong())
            val secondRegion = VapSource.Region(base, (prefix.size + first.size).toLong(), second.size.toLong())
            assertEquals(12, parseMp4Region(firstRegion).totalFrames)
            assertEquals(24, parseMp4Region(secondRegion).totalFrames)
            assertEquals(12, parseMp4Region(firstRegion).totalFrames)
            assertNotEquals(VapConfigMemoryCache.key(firstRegion), VapConfigMemoryCache.key(secondRegion))
            assertNotEquals(VapConfigMemoryCache.key(base), VapConfigMemoryCache.key(firstRegion))
            assertNotEquals(VapConfigMemoryCache.key(firstRegion), VapConfigMemoryCache.key(firstRegion.copy(length = firstRegion.length - 1L)))
            assertFailsWith<IllegalStateException> { parseMp4Region(firstRegion.copy(length = firstRegion.length - 1L)) }
            assertFailsWith<IllegalArgumentException> { parseMp4Region(firstRegion.copy(length = file.length())) }
        }
    }

    @Test
    fun assetRegionIdentityIncludesItsBounds() {
        val asset = VapSource.Asset("clip.prism")
        val region = VapSource.Region(asset, 16L, 32L)
        assertNotEquals(VapConfigMemoryCache.key(asset), VapConfigMemoryCache.key(region))
        assertNotEquals(VapConfigMemoryCache.key(region), VapConfigMemoryCache.key(region.copy(offset = 17L)))
        assertNotEquals(VapConfigMemoryCache.key(region), VapConfigMemoryCache.key(region.copy(length = 31L)))
        assertEquals(VapConfigMemoryCache.key(region), VapConfigMemoryCache.key(region.copy()))
    }

    private fun metadataBox(frames: Int): ByteArray = box(
        "vapc",
        """{"info":{"v":3,"f":$frames,"w":2,"h":2,"videoW":4,"videoH":2,"fps":30,"aFrame":[0,0,2,2],"rgbFrame":[2,0,2,2]}}""".encodeToByteArray(),
    )

    private fun box(type: String, payload: ByteArray, toEof: Boolean = false): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(if (toEof) 0 else 8 + payload.size)
            .put(type.encodeToByteArray()).put(payload).array()

    private fun withFixture(bytes: ByteArray, action: (File) -> Unit) {
        val file = Files.createTempFile("vap-region-", ".bin").toFile()
        try {
            file.writeBytes(bytes)
            action(file)
        } finally {
            Files.deleteIfExists(file.toPath())
        }
    }
}

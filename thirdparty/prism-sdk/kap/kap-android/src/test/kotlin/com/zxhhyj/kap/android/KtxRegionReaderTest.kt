package com.zxhhyj.kap.android

import com.zxhhyj.kap.core.Ktx2Info
import com.zxhhyj.kap.core.Ktx2Parser
import com.zxhhyj.kap.core.Ktx2Writer
import com.zxhhyj.kap.core.KtxFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 区域参数、逻辑读取与缓存键的纯 JVM 回归测试。 / JVM regression tests for region bounds, reads, and cache keys. */
class KtxRegionReaderTest {
    @Test fun sourceRejectsInvalidAndNestedRegionsWithoutOverflow() {
        val source = KtxAnimationSource.File("wrapped.prism")
        assertThrows(IllegalArgumentException::class.java) { KtxAnimationSource.Region(source, -1L, 1L) }
        assertThrows(IllegalArgumentException::class.java) { KtxAnimationSource.Region(source, 0L, 0L) }
        assertThrows(IllegalArgumentException::class.java) { KtxAnimationSource.Region(source, 0L, -1L) }
        assertThrows(IllegalArgumentException::class.java) { KtxAnimationSource.Region(source, Long.MAX_VALUE, 1L) }
        assertThrows(IllegalArgumentException::class.java) { KtxAnimationSource.Region(source, 1L, Long.MAX_VALUE) }
        val region = KtxAnimationSource.Region(source, 4096L, 128L)
        assertThrows(IllegalArgumentException::class.java) { KtxAnimationSource.Region(region, 0L, 1L) }
        assertEquals(Long.MAX_VALUE, KtxAnimationSource.Region(source, 0L, Long.MAX_VALUE).length)
        assertEquals(region, KtxAnimationSource.Region(source.copy(), 4096L, 128L))
        assertEquals(region.hashCode(), KtxAnimationSource.Region(source, 4096L, 128L).hashCode())
        assertNotEquals(region, region.copy(offset = 4097L))
        assertNotEquals(region, region.copy(length = 127L))
        assertNotEquals(region, KtxAnimationSource.Region(KtxAnimationSource.Asset("wrapped.prism"), 4096L, 128L))
    }

    @Test fun readsHeaderAndPayloadWithoutContainerPrefixOrSuffix() {
        val payload = payload()
        val base = MemoryReader(ByteArray(4096) { 0x55 } + payload + ByteArray(37) { 0x66 })
        RegionKtxReader(base, 4096L, payload.size.toLong()).use { reader ->
            val info = Ktx2Parser.parse(readPrefix(reader), requireLevelData = false)
            assertEquals(2, info.width)
            assertEquals(24, info.fps)
            assertEquals(payload.size.toLong(), reader.size)
            assertArrayEquals(payload, reader.readAll())
            val edge = ByteArray(4) { 0x11 }
            reader.readAt(reader.size - 1L, edge, 2, 1)
            assertArrayEquals(byteArrayOf(0x11, 0x11, payload.last(), 0x11), edge)
            reader.readAt(reader.size, edge, edge.size, 0)
            assertTrue(base.reads.all { it.offset >= 4096L && it.length <= payload.size + 4096L - it.offset })
        }
        assertTrue(base.closed)
    }

    @Test fun rejectsReadsOutsideLogicalOrDestinationBoundsBeforeDelegating() {
        val base = MemoryReader(ByteArray(64))
        val reader = RegionKtxReader(base, 10L, 20L)
        val destination = ByteArray(4)
        assertThrows(IllegalArgumentException::class.java) { reader.readAt(-1L, destination, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { reader.readAt(20L, destination, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { reader.readAt(Long.MAX_VALUE, destination, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { reader.readAt(0L, destination, 0, -1) }
        assertThrows(IllegalArgumentException::class.java) { reader.readAt(0L, destination, -1, 1) }
        assertThrows(IllegalArgumentException::class.java) { reader.readAt(0L, destination, 3, 2) }
        assertThrows(IllegalArgumentException::class.java) { reader.readAt(0L, destination, Int.MAX_VALUE, 1) }
        assertThrows(IllegalArgumentException::class.java) { reader.readAt(0L, destination, 1, Int.MAX_VALUE) }
        assertTrue(base.reads.isEmpty())
        reader.close()
    }

    @Test fun validatesActualSizeAndLongRangesBySubtraction() {
        val base = MemoryReader(ByteArray(10))
        assertThrows(IllegalArgumentException::class.java) { RegionKtxReader(base, 0L, 11L) }
        assertThrows(IllegalArgumentException::class.java) { RegionKtxReader(base, 10L, 1L) }
        assertThrows(IllegalArgumentException::class.java) { RegionKtxReader(base, Long.MAX_VALUE, 1L) }
        assertThrows(IllegalArgumentException::class.java) { RegionKtxReader(base, 1L, Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { requireKtxRange(Long.MAX_VALUE, Long.MAX_VALUE, 1L) }
        requireKtxRange(Long.MAX_VALUE, Long.MAX_VALUE - 1L, 1L)
        requireKtxRange(Long.MAX_VALUE, Long.MAX_VALUE, 0L)
    }

    @Test fun nativeRequestsTranslateSubrangesAndKeepLongOffsets() {
        val base = MemoryReader(ByteArray(0), Long.MAX_VALUE)
        val reader = RegionKtxReader(base, Long.MAX_VALUE - 10L, 10L)
        assertThrows(UnsupportedOperationException::class.java) { reader.openNativeInput(9L, 1L) }
        assertEquals(NativeRequest(Long.MAX_VALUE - 1L, 1L), base.nativeRequest)
        base.nativeRequest = null
        assertThrows(IllegalArgumentException::class.java) { reader.openNativeInput(9L, 2L) }
        assertThrows(IllegalArgumentException::class.java) { reader.openNativeInput(Long.MAX_VALUE, 1L) }
        assertEquals(null, base.nativeRequest)
        reader.close()
    }

    @Test fun oversizedHeaderPrefixCannotAllocateOrReadOutsideRegion() {
        val payload = payload()
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putLong(80, Int.MAX_VALUE.toLong())
        val base = MemoryReader(ByteArray(4096) + payload + ByteArray(64))
        RegionKtxReader(base, 4096L, payload.size.toLong()).use { reader ->
            assertThrows(IllegalArgumentException::class.java) { readPrefix(reader) }
            assertEquals(listOf(ReadRequest(4096L, Ktx2Parser.HEADER_BYTES)), base.reads)
        }
    }

    @Test fun decodedCacheSeparatesBaseSourceOffsetAndLength() {
        val base = KtxAnimationSource.File("region-cache-identity-test.prism")
        val first = KtxAnimationSource.Region(base, 10L, 20L)
        val second = first.copy(offset = 11L)
        val shorter = first.copy(length = 19L)
        val sources = listOf(base, first, second, shorter)
        try {
            var loads = 0
            sources.forEach { source ->
                KtxDecodedCache.getOrLoad(source) { byteArrayOf((++loads).toByte()) }
            }
            val cached = KtxDecodedCache.getOrLoad(first) { error("cached region must not reload") }
            assertSame(cached, KtxDecodedCache.getOrLoad(first.copy()) { error("equal source must share cache") })
            assertEquals(4, loads)
            assertArrayEquals(byteArrayOf(1), KtxDecodedCache.take(base))
            assertArrayEquals(byteArrayOf(2), KtxDecodedCache.take(first))
            assertArrayEquals(byteArrayOf(3), KtxDecodedCache.take(second))
            assertArrayEquals(byteArrayOf(4), KtxDecodedCache.take(shorter))
        } finally {
            sources.forEach(KtxDecodedCache::remove)
        }
    }

    private fun payload(): ByteArray = Ktx2Writer.write(
        KtxFormat.RGBA8,
        2,
        2,
        listOf(ByteArray(16) { (it + 1).toByte() }),
        mapOf(Ktx2Info.KEY_FPS to "24"),
    )

    private data class ReadRequest(val offset: Long, val length: Int)
    private data class NativeRequest(val offset: Long, val length: Long)

    private class MemoryReader(
        private val data: ByteArray,
        override val size: Long = data.size.toLong(),
    ) : KtxByteReader {
        val reads = mutableListOf<ReadRequest>()
        var nativeRequest: NativeRequest? = null
        var closed = false

        override fun readAt(offset: Long, destination: ByteArray, destinationOffset: Int, length: Int) {
            check(!closed)
            requireKtxRead(size, offset, destination, destinationOffset, length)
            reads += ReadRequest(offset, length)
            data.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + length)
        }

        override fun openNativeInput(offset: Long, length: Long): KtxNativeInput {
            nativeRequest = NativeRequest(offset, length)
            throw UnsupportedOperationException("No descriptor in JVM fake")
        }

        override fun close() {
            closed = true
        }
    }
}

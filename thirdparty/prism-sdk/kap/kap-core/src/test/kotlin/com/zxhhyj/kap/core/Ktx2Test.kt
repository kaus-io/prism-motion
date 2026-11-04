package com.zxhhyj.kap.core

import com.github.luben.zstd.Zstd
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Ktx2Test {
    @Test
    fun rgbaRoundTrip() {
        val layers = List(3) { index ->
            ByteArray(2 * 2 * 4) { byte -> (index + byte).toByte() }
        }
        val bytes = Ktx2Writer.write(
            format = KtxFormat.RGBA8,
            width = 2,
            height = 2,
            layers = layers,
            keyValues = mapOf(
                Ktx2Info.KEY_FPS to "24",
                Ktx2Info.KEY_LOOP to "false",
                Ktx2Info.KEY_PREMULTIPLIED to "true",
            ),
        )
        val info = Ktx2Parser.parse(bytes)
        assertEquals(KtxFormat.RGBA8, info.format)
        assertEquals(2, info.width)
        assertEquals(2, info.height)
        assertEquals(3, info.layerCount)
        assertEquals(16, info.layerBytes)
        assertEquals(24, info.fps)
        assertEquals(false, info.loop)
        assertEquals(true, info.premultiplied)
        assertEquals(48L, info.levelLength)
        for (layer in 0 until 3) {
            val offset = info.layerOffset(layer).toInt()
            assertEquals(layers[layer].toList(), bytes.copyOfRange(offset, offset + 16).toList())
        }
    }

    @Test
    fun astcLayerSizeRoundsUpToBlocks() {
        assertEquals(16, KtxFormat.ASTC_8X8.layerSize(1, 1))
        assertEquals(6 * 16, KtxFormat.ASTC_8X8.layerSize(17, 9))
        assertEquals(129_600, KtxFormat.ASTC_8X8.layerSize(720, 720))
    }

    @Test
    fun ringPatchesLayerCountAndLength() {
        val layers = List(6) { ByteArray(4) { 1 } }
        val bytes = Ktx2Writer.write(KtxFormat.RGBA8, 1, 1, layers)
        val info = Ktx2Parser.parse(bytes)
        val prefix = bytes.copyOfRange(0, info.levelOffset.toInt())
        val header = Ktx2Ring.header(prefix, info, ringSize = 3)
        val ring = header + layers.take(3).reduce { a, b -> a + b }
        val ringInfo = Ktx2Parser.parse(ring)
        assertEquals(3, ringInfo.layerCount)
        assertEquals(12L, ringInfo.levelLength)
        assertEquals(4, ringInfo.layerBytes)
    }

    @Test
    fun rejectsSupercompressedAndMultipleLevels() {
        val bytes = Ktx2Writer.write(KtxFormat.RGBA8, 1, 1, listOf(ByteArray(4)))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(44, 1)
        assertFailsWith<IllegalArgumentException> { Ktx2Parser.parse(bytes) }

        val again = Ktx2Writer.write(KtxFormat.RGBA8, 1, 1, listOf(ByteArray(4)))
        ByteBuffer.wrap(again).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 2)
        assertFailsWith<IllegalArgumentException> { Ktx2Parser.parse(again) }
    }

    @Test
    fun zstdRoundTrip() {
        val layers = List(4) { index ->
            ByteArray(16 * 16 * 4) { byte -> (index * 7 + byte % 13).toByte() }
        }
        val bytes = Ktx2Writer.write(
            KtxFormat.RGBA8,
            16,
            16,
            layers,
            mapOf(Ktx2Info.KEY_FPS to "30"),
            zstdLevel = 19,
            zstdCompressor = KtxZstdCompressor { raw, level -> Zstd.compress(raw, level) },
        )
        val info = Ktx2Parser.parse(bytes)
        assertEquals(Ktx2Info.SUPERCOMPRESSION_ZSTD, info.supercompression)
        assertEquals(true, info.isZstd)
        assertEquals(4, info.layerCount)
        assertEquals(1024, info.layerBytes)
        assertEquals(4096L, info.uncompressedLength)
        assertTrue(info.levelLength < info.uncompressedLength)
        val compressed = bytes.copyOfRange(
            info.levelOffset.toInt(),
            (info.levelOffset + info.levelLength).toInt(),
        )
        val raw = Zstd.decompress(compressed, info.uncompressedLength.toInt())
        assertEquals(info.uncompressedLength.toInt(), raw.size)
        layers.forEachIndexed { index, layer ->
            val start = index * info.layerBytes
            assertEquals(
                layer.toList(),
                raw.copyOfRange(start, start + info.layerBytes).toList(),
            )
        }
    }

    @Test
    fun astc10LayerSizeAndAlignment() {
        val bytes = Ktx2Writer.write(
            KtxFormat.ASTC_10X10,
            20,
            20,
            listOf(ByteArray(64)),
        )
        val info = Ktx2Parser.parse(bytes)
        assertEquals(4 * 16, info.layerBytes)
        assertEquals(0, info.levelOffset % 16)
        val dfdOffset = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(48)
        assertEquals(9, bytes[dfdOffset + 16].toInt())
        assertEquals(9, bytes[dfdOffset + 17].toInt())
    }

    @Test
    fun astc6DfdUsesSixBySixBlockDimensions() {
        val bytes = Ktx2Writer.write(
            KtxFormat.ASTC_6X6,
            6,
            6,
            listOf(ByteArray(16)),
        )
        val info = Ktx2Parser.parse(bytes)
        val dfdOffset = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(48)
        assertEquals(5, bytes[dfdOffset + 16].toInt())
        assertEquals(5, bytes[dfdOffset + 17].toInt())
        assertEquals(16, bytes[dfdOffset + 20].toInt())
        assertEquals(0, info.levelOffset % 16)
    }

    @Test
    fun astcAlignmentPaddingIsIncludedInPrefix() {
        val bytes = Ktx2Writer.write(
            KtxFormat.ASTC_8X8,
            1,
            1,
            listOf(ByteArray(16)),
            mapOf(Ktx2Info.KEY_FPS to "30"),
        )
        val info = Ktx2Parser.parse(bytes)
        assertEquals(0, info.levelOffset % 16)
        val prefixLength = Ktx2Parser.keyValuePrefixLength(bytes.copyOf(Ktx2Parser.HEADER_BYTES))
        assertTrue(prefixLength >= info.levelOffset)
        val prefix = bytes.copyOfRange(0, prefixLength)
        val ring = Ktx2Ring.header(prefix, info, 1) + ByteArray(16)
        assertEquals(1, Ktx2Parser.parse(ring).layerCount)
    }

    @Test
    fun keyValuePrefixCoversHeaderAndKvd() {
        val bytes = Ktx2Writer.write(
            KtxFormat.RGBA8,
            1,
            1,
            listOf(ByteArray(4)),
            mapOf(Ktx2Info.KEY_FPS to "30"),
        )
        val info = Ktx2Parser.parse(bytes)
        val prefixLength = Ktx2Parser.keyValuePrefixLength(bytes.copyOf(Ktx2Parser.HEADER_BYTES))
        assertTrue(prefixLength >= Ktx2Parser.HEADER_BYTES)
        assertTrue(prefixLength >= info.levelOffset)
    }
}

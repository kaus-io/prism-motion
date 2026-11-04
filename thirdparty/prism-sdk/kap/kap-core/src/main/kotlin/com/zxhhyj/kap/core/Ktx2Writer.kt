package com.zxhhyj.kap.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes the KTX2 subset consumed by [Ktx2Parser] and the Vulkan renderer.
 *
 * The writer is intentionally small and dependency-free so the CLI can produce
 * animation files without a `ktx` binary. ASTC encoding itself is still delegated
 * to `astcenc`; this class only packs already-encoded layers.
 *
 * 写入 [Ktx2Parser] 与 Vulkan 渲染器所需的 KTX2 子集。写入器无第三方依赖，
 * CLI 不需要 `ktx` 可执行文件；ASTC 编码仍交给 `astcenc`，这里只负责封装 layer。
 */
/** Pluggable Zstd compressor so the core module can stay platform-neutral. / 可插拔 Zstd 压缩器。 */
public fun interface KtxZstdCompressor {
    public fun compress(raw: ByteArray, level: Int): ByteArray
}

public object Ktx2Writer {
    private val MAGIC = byteArrayOf(
        0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30,
        0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private const val DFD_OFFSET: Int = 104

    // DFD templates emitted by libktx 4.3.2 for the two formats the packer supports.
    // libktx 4.3.2 为打包器支持的两种格式生成的 DFD 模板。
    private val DFD_ASTC_8X8: ByteArray = hex(
        "2c0000000000000002002800a201010007070000100000000000000000007f000000000000000000ffffffff",
    )
    private val DFD_RGBA8: ByteArray = hex(
        "5c000000000000000200580001010100000000000400000000000000000007000000000000000000" +
            "ff000000080007010000000000000000ff000000100007020000000000000000ff0000001800070f" +
            "0000000000000000ff000000",
    )

    /**
     * Pack [layers] into a single-level KTX2 animation.
     *
     * @param keyValues metadata stored in the KTX2 key/value block. The runtime
     * reads [Ktx2Info.KEY_FPS] and friends from here.
     */
    public fun write(
        format: KtxFormat,
        width: Int,
        height: Int,
        layers: List<ByteArray>,
        keyValues: Map<String, String> = emptyMap(),
        zstdLevel: Int? = null,
        zstdCompressor: KtxZstdCompressor? = null,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        writeTo(output, format, width, height, layers, keyValues, zstdLevel, zstdCompressor)
        return output.toByteArray()
    }

    /** Streaming variant of [write]. / [write] 的流式版本。 */
    public fun writeTo(
        output: OutputStream,
        format: KtxFormat,
        width: Int,
        height: Int,
        layers: List<ByteArray>,
        keyValues: Map<String, String> = emptyMap(),
        zstdLevel: Int? = null,
        zstdCompressor: KtxZstdCompressor? = null,
    ) {
        require(layers.isNotEmpty()) { "at least one layer is required" }
        require(width > 0 && height > 0) { "width/height must be positive" }
        require(zstdLevel == null || zstdLevel in 1..22) {
            "zstdLevel must be in 1..22"
        }
        require(zstdLevel == null || zstdCompressor != null) {
            "a zstdCompressor is required when zstdLevel is set"
        }
        val dfd = when (format) {
            KtxFormat.ASTC_8X8 -> DFD_ASTC_8X8
            KtxFormat.ASTC_4X4, KtxFormat.ASTC_6X6,
            KtxFormat.ASTC_10X10, KtxFormat.ASTC_12X12 -> DFD_ASTC_8X8.copyOf().also {
                // The ASTC DFD differs only in the texel block dimensions (bytes 16/17).
                // ASTC DFD 仅块尺寸字段（偏移 16/17）不同。
                it[16] = (format.blockWidth - 1).toByte()
                it[17] = (format.blockHeight - 1).toByte()
            }
            KtxFormat.RGBA8 -> DFD_RGBA8
            else -> error(
                "KTX2 writer supports ASTC 4x4 / 6x6 / 8x8 and RGBA8; got ${format.displayName}",
            )
        }
        val layerBytes = format.layerSize(width, height)
        layers.forEachIndexed { index, layer ->
            require(layer.size == layerBytes) {
                "layer $index is ${layer.size} bytes, expected $layerBytes for ${format.displayName} " +
                    "${width}x$height"
            }
        }
        val metadata = keyValues.toMutableMap()
        metadata.putIfAbsent(Ktx2Info.KEY_VERSION, "1")
        metadata.putIfAbsent("KTXwriter", "kap-cli 0.1.0")
        metadata.putIfAbsent("anim.zstd", (zstdLevel != null).toString())
        val kvd = buildKeyValues(metadata)
        val kvdOffset = DFD_OFFSET + dfd.size
        val rawLength = layerBytes.toLong() * layers.size
        require(rawLength <= Int.MAX_VALUE) { "KTX2 payload is too large for this writer" }
        val storedData: ByteArray? = if (zstdLevel != null) {
            val raw = ByteArray(rawLength.toInt())
            var offset = 0
            layers.forEach { layer ->
                System.arraycopy(layer, 0, raw, offset, layer.size)
                offset += layer.size
            }
            zstdCompressor!!.compress(raw, zstdLevel)
        } else {
            null
        }
        val storedLength = storedData?.size?.toLong() ?: rawLength
        // KTX2 requires uncompressed level data to be lcm(texel block size, 4) aligned;
        // supercompressed payloads only need 4-byte alignment.
        // 未压缩 level 按 lcm(块字节数, 4) 对齐；超压缩数据只需 4 字节对齐。
        val alignment = if (zstdLevel == null) maxOf(4, format.blockBytes) else 4
        val levelOffset = align(kvdOffset + kvd.size, alignment)
        require(levelOffset.toLong() + storedLength <= Int.MAX_VALUE) {
            "KTX2 payload is too large for this writer"
        }

        val header = ByteArray(levelOffset)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC)
        buffer.putInt(format.vkFormat)
        buffer.putInt(1) // typeSize
        buffer.putInt(width)
        buffer.putInt(height)
        buffer.putInt(0) // pixelDepth
        buffer.putInt(layers.size)
        buffer.putInt(1) // faceCount
        buffer.putInt(1) // levelCount
        buffer.putInt(if (zstdLevel != null) Ktx2Info.SUPERCOMPRESSION_ZSTD else 0)
        buffer.putInt(DFD_OFFSET)
        buffer.putInt(dfd.size)
        buffer.putInt(kvdOffset)
        buffer.putInt(kvd.size)
        buffer.putLong(0L) // sgdByteOffset
        buffer.putLong(0L) // sgdByteLength
        buffer.putLong(levelOffset.toLong())
        buffer.putLong(storedLength)
        buffer.putLong(rawLength)
        System.arraycopy(dfd, 0, header, DFD_OFFSET, dfd.size)
        System.arraycopy(kvd, 0, header, kvdOffset, kvd.size)

        output.write(header)
        if (storedData != null) {
            output.write(storedData)
        } else {
            layers.forEach(output::write)
        }
        output.flush()
    }

    private fun buildKeyValues(values: Map<String, String>): ByteArray {
        if (values.isEmpty()) return ByteArray(0)
        val output = ByteArrayOutputStream()
        // KTX2 requires keys to be sorted.
        // KTX2 要求键按字典序排列。
        for ((key, value) in values.toSortedMap()) {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val valueBytes = value.toByteArray(Charsets.UTF_8)
            val rawLength = keyBytes.size + 1 + valueBytes.size
            val entryLength = align(rawLength, 4)
            val entry = ByteArray(entryLength)
            System.arraycopy(keyBytes, 0, entry, 0, keyBytes.size)
            entry[keyBytes.size] = 0
            System.arraycopy(valueBytes, 0, entry, keyBytes.size + 1, valueBytes.size)
            val lengthBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(entryLength)
                .array()
            output.write(lengthBytes)
            output.write(entry)
        }
        return output.toByteArray()
    }

    private fun align(value: Int, alignment: Int): Int =
        (value + alignment - 1) / alignment * alignment

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0) { "odd hex length" }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}

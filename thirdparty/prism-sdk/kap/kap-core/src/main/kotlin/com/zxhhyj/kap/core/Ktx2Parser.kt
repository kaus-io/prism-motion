package com.zxhhyj.kap.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal KTX2 reader used by both the Android runtime and the CLI.
 *
 * Only the subset needed by the animation runtime is accepted:
 * - `KTX 20` identifier
 * - one mip level, one face
 * - 2D, no depth / one depth slice
 * - no supercompression
 *
 * 运行时与 CLI 共用的最小 KTX2 读取器。只接受动画所需的子集：
 * 单 mip、单面、2D、无 supercompression。
 */
public object Ktx2Parser {
    public const val HEADER_BYTES: Int = 104
    private const val LEVEL_INDEX_OFFSET: Int = 80
    private val MAGIC = byteArrayOf(
        0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30,
        0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /**
     * Parse a complete file. [bytes] must contain the level data.
     * 解析完整文件，[bytes] 必须包含 level 数据。
     */
    public fun parse(bytes: ByteArray): Ktx2Info = parse(bytes, requireLevelData = true)

    /**
     * Parse only the header / key-value prefix. Useful for lazy loading; the
     * level range is validated against the declared file size only when
     * [requireLevelData] is true.
     *
     * 只解析头部 / KVD 前缀，用于懒加载；仅当 [requireLevelData] 为 true 时
     * 才校验 level 数据是否真的在 [bytes] 内。
     */
    public fun parse(bytes: ByteArray, requireLevelData: Boolean): Ktx2Info {
        require(bytes.size >= HEADER_BYTES) { "truncated KTX2 header: ${bytes.size} bytes" }
        for (i in MAGIC.indices) {
            require(bytes[i] == MAGIC[i]) { "not a KTX2 file" }
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val vkFormat = buffer.getInt(12)
        val width = buffer.getInt(20)
        val height = buffer.getInt(24)
        val depth = buffer.getInt(28)
        val layers = buffer.getInt(32)
        val faces = buffer.getInt(36)
        val levels = buffer.getInt(40)
        val supercompression = buffer.getInt(44)
        val dfdOffset = buffer.getInt(48)
        val dfdLength = buffer.getInt(52)
        val kvdOffset = buffer.getInt(56)
        val kvdLength = buffer.getInt(60)
        val sgdLength = buffer.getLong(72)
        val levelOffset = buffer.getLong(LEVEL_INDEX_OFFSET)
        val levelLength = buffer.getLong(LEVEL_INDEX_OFFSET + 8)
        val uncompressedLength = buffer.getLong(LEVEL_INDEX_OFFSET + 16)

        require(width > 0 && height > 0) { "invalid KTX2 size ${width}x$height" }
        require(depth == 0 || depth == 1) { "3D textures are not supported (depth=$depth)" }
        require(layers > 0) { "KTX2 must contain at least one layer" }
        require(faces == 1) { "cube maps are not supported (faces=$faces)" }
        require(levels == 1) { "only one mip level is supported (levels=$levels)" }
        require(
            supercompression == Ktx2Info.SUPERCOMPRESSION_NONE ||
                supercompression == Ktx2Info.SUPERCOMPRESSION_ZSTD,
        ) {
            "unsupported KTX2 supercompression scheme=$supercompression"
        }
        require(sgdLength == 0L) { "supercompression global data is not supported" }
        require(levelOffset >= HEADER_BYTES.toLong()) { "invalid level offset $levelOffset" }
        require(levelLength > 0) { "empty level data" }
        val rawLength = when (supercompression) {
            Ktx2Info.SUPERCOMPRESSION_NONE -> {
                require(uncompressedLength == 0L || uncompressedLength == levelLength) {
                    "uncompressed length mismatch: $uncompressedLength != $levelLength"
                }
                levelLength
            }

            else -> {
                require(uncompressedLength > 0L) { "missing Zstd uncompressed length" }
                uncompressedLength
            }
        }
        require(rawLength % layers == 0L) {
            "uncompressed level length $rawLength is not divisible by $layers layers"
        }
        if (requireLevelData) {
            require(levelOffset + levelLength <= bytes.size.toLong()) {
                "truncated KTX2 level: ${levelOffset + levelLength} > ${bytes.size}"
            }
        }
        val layerBytes = (rawLength / layers).toInt()
        val keyValues = parseKeyValues(bytes, kvdOffset, kvdLength)
        val format = KtxFormat.fromVkFormat(vkFormat)
        if (format != null) {
            val expected = format.layerSize(width, height)
            require(expected == layerBytes) {
                "layer size mismatch: header=$layerBytes expected=$expected for ${format.displayName} " +
                    "${width}x$height"
            }
        }
        return Ktx2Info(
            vkFormat = vkFormat,
            format = format,
            width = width,
            height = height,
            layerCount = layers,
            levelCount = levels,
            supercompression = supercompression,
            levelOffset = levelOffset,
            levelLength = levelLength,
            uncompressedLength = rawLength,
            layerBytes = layerBytes,
            keyValues = keyValues,
        )
    }

    private fun parseKeyValues(bytes: ByteArray, offset: Int, length: Int): Map<String, String> {
        if (length <= 0 || offset < 0 || offset >= bytes.size) return emptyMap()
        val end = minOf(bytes.size.toLong(), offset.toLong() + length).toInt()
        val result = LinkedHashMap<String, String>()
        var cursor = offset
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (cursor + 4 <= end) {
            val entryLength = buffer.getInt(cursor)
            cursor += 4
            if (entryLength <= 0 || cursor + entryLength > end) break
            val entryEnd = cursor + entryLength
            var keyEnd = cursor
            while (keyEnd < entryEnd && bytes[keyEnd] != 0.toByte()) keyEnd++
            if (keyEnd > cursor) {
                val key = String(bytes, cursor, keyEnd - cursor, Charsets.UTF_8)
                val valueStart = minOf(keyEnd + 1, entryEnd)
                val value = String(bytes, valueStart, entryEnd - valueStart, Charsets.UTF_8)
                    .trimEnd('\u0000')
                result[key] = value
            }
            cursor = entryEnd
        }
        return result
    }

    /** Read just enough bytes to cover the header and the key-value block. / 读取头部与 KVD 的最小前缀。 */
    public fun keyValuePrefixLength(header: ByteArray): Int {
        require(header.size >= HEADER_BYTES) { "truncated KTX2 header" }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val kvdOffset = buffer.getInt(56)
        val kvdLength = buffer.getInt(60)
        val dfdOffset = buffer.getInt(48)
        val dfdLength = buffer.getInt(52)
        val levelOffset = buffer.getLong(80)
        require(levelOffset <= Int.MAX_VALUE) { "KTX2 level offset is too large" }
        // The level offset may include alignment padding after the KVD block.
        // level offset 可能包含 KVD 之后的对齐填充，必须一起读入前缀。
        val end = maxOf(
            HEADER_BYTES,
            kvdOffset + kvdLength.coerceAtLeast(0),
            dfdOffset + dfdLength.coerceAtLeast(0),
            levelOffset.toInt(),
        )
        return end.coerceAtLeast(HEADER_BYTES)
    }
}

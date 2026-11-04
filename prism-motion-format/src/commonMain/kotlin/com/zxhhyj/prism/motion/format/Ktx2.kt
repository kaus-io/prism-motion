package com.zxhhyj.prism.motion.format

/** KTX2 子集写入：ASTC 或 RGBA8 的单 level、多层（帧）动画容器，与 kap-core 的格式一致。 */
public enum class Ktx2Format(
    val vkFormat: Int,
    val blockW: Int,
    val blockH: Int,
    val blockBytes: Int,
) {
    RGBA8(37, 1, 1, 4),
    ASTC_4X4(157, 4, 4, 16),
    ASTC_6X6(165, 6, 6, 16),
    ASTC_8X8(171, 8, 8, 16),
    ;

    fun layerSize(width: Int, height: Int): Int {
        val blocksW = (width + blockW - 1) / blockW
        val blocksH = (height + blockH - 1) / blockH
        return blocksW * blocksH * blockBytes
    }
}

public object Ktx2 {

    private val MAGIC = byteArrayOf(
        0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30,
        0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private const val DFD_OFFSET = 104

    private val DFD_ASTC = hex(
        "2c0000000000000002002800a201010007070000100000000000000000007f000000000000000000ffffffff",
    )

    private val DFD_RGBA8 = hex(
        "5c000000000000000200580001010100000000000400000000000000000007000000000000000000" +
            "ff000000080007010000000000000000ff000000100007020000000000000000ff0000001800070f" +
            "0000000000000000ff000000",
    )

    public fun write(
        format: Ktx2Format,
        width: Int,
        height: Int,
        layers: List<ByteArray>,
        keyValues: Map<String, String>,
    ): ByteArray {
        require(layers.isNotEmpty()) { "至少需要一帧" }
        val layerBytes = format.layerSize(width, height)
        layers.forEachIndexed { index, layer ->
            require(layer.size == layerBytes) {
                "第 $index 帧是 ${layer.size} 字节，$format ${width}x$height 需要 $layerBytes"
            }
        }

        val dfd = when (format) {
            Ktx2Format.RGBA8 -> DFD_RGBA8
            else -> DFD_ASTC.copyOf().also {
                it[16] = (format.blockW - 1).toByte()
                it[17] = (format.blockH - 1).toByte()
            }
        }

        val metadata = LinkedHashMap<String, String>()
        for ((key, value) in keyValues) metadata[key] = value
        if ("anim.version" !in metadata) metadata["anim.version"] = "1"
        if ("KTXwriter" !in metadata) metadata["KTXwriter"] = "prism-motion-cli-native"
        if ("anim.zstd" !in metadata) metadata["anim.zstd"] = "false"
        val kvd = buildKeyValues(metadata)

        val kvdOffset = DFD_OFFSET + dfd.size
        val alignment = maxOf(4, format.blockBytes)
        val levelOffset = align(kvdOffset + kvd.size, alignment)
        val rawLength = layerBytes.toLong() * layers.size

        val header = ByteArray(levelOffset)
        MAGIC.copyInto(header, 0)
        putInt(header, 12, format.vkFormat)
        putInt(header, 16, 1) // typeSize
        putInt(header, 20, width)
        putInt(header, 24, height)
        putInt(header, 28, 0) // pixelDepth
        putInt(header, 32, layers.size)
        putInt(header, 36, 1) // faceCount
        putInt(header, 40, 1) // levelCount
        putInt(header, 44, 0) // supercompression
        putInt(header, 48, DFD_OFFSET)
        putInt(header, 52, dfd.size)
        putInt(header, 56, kvdOffset)
        putInt(header, 60, kvd.size)
        putLong(header, 64, 0L) // sgdByteOffset
        putLong(header, 72, 0L) // sgdByteLength
        putLong(header, 80, levelOffset.toLong())
        putLong(header, 88, rawLength)
        putLong(header, 96, rawLength)
        dfd.copyInto(header, DFD_OFFSET)
        kvd.copyInto(header, kvdOffset)

        val total = header.size + rawLength.toInt()
        val out = ByteArray(total)
        header.copyInto(out, 0)
        var offset = header.size
        for (layer in layers) {
            layer.copyInto(out, offset)
            offset += layer.size
        }
        return out
    }

    private fun buildKeyValues(values: Map<String, String>): ByteArray {
        if (values.isEmpty()) return ByteArray(0)
        val entries = ArrayList<ByteArray>()
        for (key in values.keys.sorted()) {
            val value = values.getValue(key)
            val keyBytes = key.encodeToByteArray()
            val valueBytes = value.encodeToByteArray()
            val raw = keyBytes.size + 1 + valueBytes.size
            val length = align(raw, 4)
            val entry = ByteArray(4 + length)
            putInt(entry, 0, length)
            keyBytes.copyInto(entry, 4)
            entry[4 + keyBytes.size] = 0
            valueBytes.copyInto(entry, 5 + keyBytes.size)
            entries += entry
        }
        var total = 0
        for (entry in entries) total += entry.size
        val out = ByteArray(total)
        var offset = 0
        for (entry in entries) {
            entry.copyInto(out, offset)
            offset += entry.size
        }
        return out
    }

    private fun align(value: Int, alignment: Int): Int =
        (value + alignment - 1) / alignment * alignment

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) target[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    private fun putLong(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) target[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
}

/** 读出来的 KTX2 动画：元数据 + 未解码的 layer 数据。 / Parsed KTX2: metadata plus undecoded layer payloads. */
public class Ktx2Animation(
    public val width: Int,
    public val height: Int,
    public val fps: Int,
    public val format: Ktx2Format?,
    public val vkFormat: Int,
    public val layers: List<ByteArray>,
    public val keyValues: Map<String, String>,
)

/** KTX2 读取：只做容器解析，不解码像素。 / KTX2 reading: container parsing only, no pixel decoding. */
public object Ktx2Reader {

    private val MAGIC = byteArrayOf(
        0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30,
        0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
    )

    public fun isKtx2(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    public fun read(bytes: ByteArray): Ktx2Animation {
        require(bytes.size > 104 && isKtx2(bytes)) { "不是 KTX2 文件" }
        val vkFormat = Bytes.u32le(bytes, 12).toInt()
        val width = Bytes.u32le(bytes, 20).toInt()
        val height = Bytes.u32le(bytes, 24).toInt()
        val layerCount = Bytes.u32le(bytes, 32).toInt()
        val kvdOffset = Bytes.u32le(bytes, 56).toInt()
        val kvdLength = Bytes.u32le(bytes, 60).toInt()
        val levelOffset = Bytes.u64le(bytes, 80).toInt()
        val levelLength = Bytes.u64le(bytes, 88).toInt()

        val keyValues = readKeyValues(bytes, kvdOffset, kvdLength)
        val fps = keyValues["anim.fps"]?.toIntOrNull()?.takeIf { it > 0 } ?: 30
        val format = Ktx2Format.entries.firstOrNull { it.vkFormat == vkFormat }

        require(layerCount > 0) { "KTX2 没有帧" }
        require(levelLength % layerCount == 0) { "level 长度无法被帧数整除" }
        val layerSize = levelLength / layerCount
        val layers = List(layerCount) { index ->
            val start = levelOffset + index * layerSize
            require(start + layerSize <= bytes.size) { "KTX2 layer 越界" }
            bytes.copyOfRange(start, start + layerSize)
        }
        return Ktx2Animation(width, height, fps, format, vkFormat, layers, keyValues)
    }

    private fun readKeyValues(bytes: ByteArray, offset: Int, length: Int): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var position = offset
        val end = (offset + length).coerceAtMost(bytes.size)
        while (position + 4 <= end) {
            val entryLength = Bytes.u32le(bytes, position).toInt()
            position += 4
            if (entryLength <= 0 || position + entryLength > end) break
            val entry = bytes.decodeToString(position, position + entryLength)
            position += entryLength
            position += (4 - entryLength % 4) % 4
            val separator = entry.indexOf('\u0000')
            if (separator > 0) {
                result[entry.substring(0, separator).trimEnd('\u0000')] =
                    entry.substring(separator + 1).trimEnd('\u0000')
            }
        }
        return result
    }
}

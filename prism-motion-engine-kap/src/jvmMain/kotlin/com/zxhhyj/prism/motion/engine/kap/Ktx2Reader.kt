package com.zxhhyj.prism.motion.engine.kap

/** 解码后的一帧 RGBA 像素 / one decoded RGBA frame. */
internal class Ktx2Frame(val width: Int, val height: Int, val rgba: ByteArray)

/** 一个 KTX2 动画的解析结果 / parsed KTX2 animation. */
internal class Ktx2Animation(
    val width: Int,
    val height: Int,
    val fps: Int,
    val frames: List<Ktx2Frame>,
)

/**
 * 读取 KTX2 动画（单 level、多 layer）并解出 RGBA 帧。
 * Reads a single-level, multi-layer KTX2 animation and decodes RGBA frames.
 *
 * RGBA8 直接切片；ASTC 走外部 astcenc 解压（找不到时抛错并给出安装提示）。
 */
internal object Ktx2Reader {

    private val MAGIC = byteArrayOf(
        0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30,
        0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun read(bytes: ByteArray): Ktx2Animation {
        require(bytes.size > 104 && MAGIC.indices.all { bytes[it] == MAGIC[it] }) { "不是 KTX2 文件" }
        val vkFormat = u32(bytes, 12).toInt()
        val width = u32(bytes, 20).toInt()
        val height = u32(bytes, 24).toInt()
        val layers = u32(bytes, 32).toInt()
        val kvdOffset = u32(bytes, 56).toInt()
        val kvdLength = u32(bytes, 60).toInt()
        val levelOffset = u64(bytes, 80).toInt()
        val levelLength = u64(bytes, 88).toInt()

        val metadata = parseKeyValues(bytes, kvdOffset, kvdLength)
        val fps = metadata["anim.fps"]?.toIntOrNull()?.takeIf { it > 0 } ?: 30

        require(layers > 0) { "KTX2 没有帧" }
        require(levelLength % layers == 0) { "level 长度无法被帧数整除" }
        val layerBytes = levelLength / layers

        val frames = ArrayList<Ktx2Frame>(layers)
        for (index in 0 until layers) {
            val start = levelOffset + index * layerBytes
            val raw = bytes.copyOfRange(start, start + layerBytes)
            val rgba = when (vkFormat) {
                VK_RGBA8 -> raw
                VK_ASTC_4X4, VK_ASTC_6X6, VK_ASTC_8X8, VK_ASTC_10X10, VK_ASTC_12X12 ->
                    AstcDecoder.decode(raw, width, height, vkFormat)
                else -> error("不支持的 vkFormat=$vkFormat")
            }
            frames += Ktx2Frame(width, height, rgba)
        }
        return Ktx2Animation(width, height, fps, frames)
    }

    private const val VK_RGBA8 = 37
    private const val VK_ASTC_4X4 = 157
    private const val VK_ASTC_6X6 = 165
    private const val VK_ASTC_8X8 = 171
    private const val VK_ASTC_10X10 = 179
    private const val VK_ASTC_12X12 = 183

    private fun parseKeyValues(bytes: ByteArray, offset: Int, length: Int): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var position = offset
        val end = (offset + length).coerceAtMost(bytes.size)
        while (position + 4 <= end) {
            val entryLength = u32(bytes, position).toInt()
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

    private fun u32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 3 downTo 0) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }

    private fun u64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }
}

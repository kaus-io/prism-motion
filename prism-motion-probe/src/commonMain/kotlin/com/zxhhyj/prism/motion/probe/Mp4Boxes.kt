package com.zxhhyj.prism.motion.probe

/**
 * 最小 ISO BMFF（MP4）box 遍历器：只做边界校验，不解释 box 内容。
 * 只支持 32/64 位 size 与 size=0（延伸到文件末尾）三种情形。
 */
internal class Mp4Boxes(private val data: ByteArray) {

    class Box(val type: String, val offset: Int, val payload: Int, val end: Int) {
        val size: Int get() = end - offset
    }

    var truncated: Boolean = false
        private set

    fun topLevel(): List<Box> = parse(0, data.size)

    fun children(parent: Box): List<Box> = parse(parent.payload, parent.end)

    fun find(boxes: List<Box>, vararg path: String): Box? {
        var current = boxes
        var found: Box? = null
        for (name in path) {
            found = current.firstOrNull { it.type == name } ?: return null
            current = children(found)
        }
        return found
    }

    fun u8(offset: Int): Int = data[offset].toInt() and 0xFF

    fun u16(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)

    fun u32(offset: Int): Long =
        (u8(offset).toLong() shl 24) or (u8(offset + 1).toLong() shl 16) or
            (u8(offset + 2).toLong() shl 8) or u8(offset + 3).toLong()

    fun u64(offset: Int): Long = (u32(offset) shl 32) or u32(offset + 4)

    /** box 类型是 4 个 ASCII 字节；逐字节转字符，JVM 与 Native 通用。 */
    fun text(offset: Int, length: Int): String = buildString(length) {
        for (i in 0 until length) append((data[offset + i].toInt() and 0xFF).toChar())
    }

    fun payload(box: Box): ByteArray = data.copyOfRange(box.payload, box.end)

    private fun parse(start: Int, limit: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = start
        while (p + 8 <= limit) {
            var size = u32(p)
            var header = 8
            if (size == 1L) {
                if (p + 16 > limit) {
                    truncated = true
                    break
                }
                size = u64(p + 8)
                header = 16
            } else if (size == 0L) {
                size = (limit - p).toLong()
            }
            val type = text(p + 4, 4)
            if (size < header || p + size > limit) {
                truncated = true
                break
            }
            out += Box(type, p, p + header, p + size.toInt())
            p += size.toInt()
        }
        return out
    }
}

package com.zxhhyj.prism.motion.format

/** 一个顶层或嵌套的 MP4 box 边界。 / Bounds of one MP4 box, top-level or nested. */
public class Mp4Box(
    public val type: String,
    public val offset: Int,
    public val payload: Int,
    public val end: Int,
) {
    public val size: Int get() = end - offset
}

/** 最小 ISO BMFF 遍历器：只做边界校验，不解释内容。 / Minimal ISO BMFF walker with bounds checks only. */
public object Mp4 {

    public fun boxes(bytes: ByteArray, start: Int = 0, limit: Int = bytes.size): List<Mp4Box> {
        val result = ArrayList<Mp4Box>()
        var position = start
        while (position + 8 <= limit) {
            var size = Bytes.u32be(bytes, position)
            val type = Bytes.typeName(bytes, position + 4)
            var header = 8
            if (size == 1L) {
                if (position + 16 > limit) break
                size = Bytes.u64le(bytes, position + 8)
                header = 16
            } else if (size == 0L) {
                size = (limit - position).toLong()
            }
            if (size < header || position + size > limit) break
            result += Mp4Box(type, position, position + header, position + size.toInt())
            position += size.toInt()
        }
        return result
    }

    public fun children(bytes: ByteArray, box: Mp4Box): List<Mp4Box> = boxes(bytes, box.payload, box.end)

    /** 在第 [position] 个顶层 box 前插入一个完整 box。 / Inserts a full box before top-level box [position]. */
    public fun insertTopLevel(bytes: ByteArray, box: ByteArray, position: Int = 3): ByteArray {
        val top = boxes(bytes).filter { it.size >= 8 }
        val at = position.coerceIn(0, top.size)
        val out = ByteArray(bytes.size + box.size)
        var dst = 0
        for (i in 0..top.size) {
            if (i == at) {
                box.copyInto(out, dst)
                dst += box.size
            }
            if (i < top.size) {
                val start = top[i].offset
                val end = if (i + 1 < top.size) top[i + 1].offset else bytes.size
                bytes.copyInto(out, dst, start, end)
                dst += end - start
            }
        }
        return out
    }

    public fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        Bytes.putU32be(out, 0, out.size)
        for (i in 0 until 4) out[4 + i] = type[i].code.toByte()
        payload.copyInto(out, 8)
        return out
    }
}

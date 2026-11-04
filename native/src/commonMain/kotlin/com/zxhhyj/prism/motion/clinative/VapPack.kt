package com.zxhhyj.prism.motion.clinative

/** VAP 画布布局与帧打包，规则与 vap-encode 的 EncodeLayout / AlphaFramePacker 一致。 */
internal object VapPack {

    data class Layout(
        val rgbW: Int,
        val rgbH: Int,
        val alphaX: Int,
        val alphaY: Int,
        val alphaW: Int,
        val alphaH: Int,
        val videoW: Int,
        val videoH: Int,
        val hasAlpha: Boolean,
    )

    fun computeLayout(width: Int, height: Int, scale: Float, hasAlpha: Boolean): Layout {
        if (!hasAlpha) {
            val fillW = fill16(width)
            val fillH = fill16(height)
            return Layout(width, height, 0, 0, 0, 0, width + fillW, height + fillH, false)
        }
        val s = scale.coerceIn(0.5f, 1f)
        val alphaW = (width * s).toInt()
        val alphaH = (height * s).toInt()
        val gap = 4

        val hMax = maxOf(width + gap + alphaW, height)
        val vMax = maxOf(width, height + gap + alphaH)
        val vertical = hMax > vMax

        val alphaX: Int
        val alphaY: Int
        val rawW: Int
        val rawH: Int
        if (vertical) {
            alphaX = 0
            alphaY = height + gap
            rawW = width
            rawH = height + gap + alphaH
        } else {
            alphaX = width + gap
            alphaY = 0
            rawW = width + gap + alphaW
            rawH = height
        }
        val fillW = fill16(rawW)
        val fillH = fill16(rawH)
        return Layout(width, height, alphaX, alphaY, alphaW, alphaH, rawW + fillW, rawH + fillH, true)
    }

    /** 把一帧 RGBA 打包成不透明 RGBA 画布（RGB 合成到黑底，alpha 转灰度放蒙版区）。 */
    fun packFrame(source: ByteArray, width: Int, height: Int, layout: Layout, out: ByteArray) {
        out.fill(0)
        val stride = layout.videoW * 4
        var offset = 0
        while (offset < out.size) {
            out[offset + 3] = 255.toByte()
            offset += 4
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val src = (y * width + x) * 4
                val alpha = (source[src + 3].toInt() and 0xFF)
                val dst = y * stride + x * 4
                out[dst] = (((source[src].toInt() and 0xFF) * alpha) / 255).toByte()
                out[dst + 1] = (((source[src + 1].toInt() and 0xFF) * alpha) / 255).toByte()
                out[dst + 2] = (((source[src + 2].toInt() and 0xFF) * alpha) / 255).toByte()
                out[dst + 3] = 255.toByte()
            }
        }
        if (!layout.hasAlpha) return
        for (ay in 0 until layout.alphaH) {
            val sy = ay * height / layout.alphaH
            for (ax in 0 until layout.alphaW) {
                val sx = ax * width / layout.alphaW
                val value = (source[(sy * width + sx) * 4 + 3].toInt() and 0xFF)
                val dst = (layout.alphaY + ay) * stride + (layout.alphaX + ax) * 4
                out[dst] = value.toByte()
                out[dst + 1] = value.toByte()
                out[dst + 2] = value.toByte()
                out[dst + 3] = 255.toByte()
            }
        }
    }

    fun vapcJson(
        frames: Int,
        width: Int,
        height: Int,
        layout: Layout,
        fps: Int,
    ): String {
        val alphaRect = if (layout.hasAlpha) {
            "[${layout.alphaX},${layout.alphaY},${layout.alphaW},${layout.alphaH}]"
        } else {
            "[0,0,0,0]"
        }
        return """{"info":{"v":3,"f":$frames,"w":$width,"h":$height,"videoW":${layout.videoW},"videoH":${layout.videoH},"fps":$fps,"aFrame":$alphaRect,"rgbFrame":[0,0,$width,$height],"hasAlpha":${layout.hasAlpha}}}"""
    }

    /** 在顶层 box 的第 [position] 个之前插入一个完整 box。 */
    fun insertBox(mp4: ByteArray, box: ByteArray, position: Int = 3): ByteArray {
        val offsets = ArrayList<Int>()
        var p = 0
        while (p + 8 <= mp4.size) {
            var size = readIntBe(mp4, p)
            val type = CharArray(4) { mp4[p + 4 + it].toInt().toChar() }.concatToString()
            if (size == 1) return mp4 // 不支持 64 位长度，直接原样返回
            if (size == 0) size = mp4.size - p
            if (size < 8 || p + size > mp4.size) break
            check(type.length == 4)
            offsets += p
            p += size
        }
        val at = position.coerceIn(0, offsets.size)
        val out = ByteArray(mp4.size + box.size)
        var dst = 0
        for (i in 0..offsets.size) {
            if (i == at) {
                box.copyInto(out, dst)
                dst += box.size
            }
            if (i < offsets.size) {
                val start = offsets[i]
                val end = if (i + 1 < offsets.size) offsets[i + 1] else mp4.size
                mp4.copyInto(out, dst, start, end)
                dst += end - start
            }
        }
        return out
    }

    fun boxBytes(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        writeIntBe(out, 0, out.size)
        for (i in 0 until 4) out[4 + i] = type[i].code.toByte()
        payload.copyInto(out, 8)
        return out
    }

    private fun fill16(value: Int): Int = if (value % 16 == 0) 0 else ((value / 16) + 1) * 16 - value

    private fun readIntBe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun writeIntBe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}

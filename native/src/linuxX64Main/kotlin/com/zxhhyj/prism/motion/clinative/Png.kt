package com.zxhhyj.prism.motion.clinative

/** 一张 PNG 的最小信息：只读 IHDR，不解码像素。 */
internal object Png {
    fun size(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24) return null
        if (bytes[0] != 0x89.toByte() || bytes[1] != 'P'.code.toByte() ||
            bytes[2] != 'N'.code.toByte() || bytes[3] != 'G'.code.toByte()
        ) {
            return null
        }
        val width = readIntBe(bytes, 16)
        val height = readIntBe(bytes, 20)
        if (width <= 0 || height <= 0) return null
        return width to height
    }

    private fun readIntBe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}

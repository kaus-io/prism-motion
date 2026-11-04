package com.zxhhyj.prism.motion.format

/** 字节序读写工具：容器解析里到处都要用。 / Byte-order helpers shared by every container parser. */
public object Bytes {

    public fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF

    public fun u16be(bytes: ByteArray, offset: Int): Int = (u8(bytes, offset) shl 8) or u8(bytes, offset + 1)

    public fun u32be(bytes: ByteArray, offset: Int): Long =
        (u8(bytes, offset).toLong() shl 24) or (u8(bytes, offset + 1).toLong() shl 16) or
            (u8(bytes, offset + 2).toLong() shl 8) or u8(bytes, offset + 3).toLong()

    public fun u32le(bytes: ByteArray, offset: Int): Long =
        (u8(bytes, offset).toLong()) or (u8(bytes, offset + 1).toLong() shl 8) or
            (u8(bytes, offset + 2).toLong() shl 16) or (u8(bytes, offset + 3).toLong() shl 24)

    public fun u64le(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or u8(bytes, offset + i).toLong()
        return value
    }

    public fun putU32be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    public fun putU32le(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    public fun putU64le(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) target[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    /** ISO BMFF 的 4 字节类型名。 / Four-character ISO BMFF box type. */
    public fun typeName(bytes: ByteArray, offset: Int): String = buildString(4) {
        for (i in 0 until 4) append((bytes[offset + i].toInt() and 0xFF).toChar())
    }
}

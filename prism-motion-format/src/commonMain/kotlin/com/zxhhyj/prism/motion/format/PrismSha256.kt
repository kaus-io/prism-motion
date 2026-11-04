package com.zxhhyj.prism.motion.format

/**
 * 无平台依赖的 SHA-256，按块读取，不复制完整输入。 / Platform-independent SHA-256 that reads blocks without copying the entire input.
 * 压缩算法改编自本仓库 probe/Sha256.kt。 / Compression algorithm adapted from this repository's probe/Sha256.kt.
 */
public object PrismSha256 {
    private val roundConstants = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )

    /** 指定字节范围的 32 字节摘要；范围错误抛出 IllegalArgumentException。 / Returns a 32-byte range digest; invalid ranges throw IllegalArgumentException. */
    public fun digest(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ByteArray {
        require(offset >= 0 && offset <= bytes.size && length >= 0 && length <= bytes.size - offset) {
            "Invalid SHA-256 input range"
        }
        val state = intArrayOf(
            0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6,
            0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19,
        )
        val schedule = IntArray(64)
        val completeLength = length - length % 64
        var position = 0
        while (position < completeLength) {
            compress(bytes, offset + position, state, schedule)
            position += 64
        }

        val remaining = length - completeLength
        val tail = ByteArray(if (remaining < 56) 64 else 128)
        bytes.copyInto(tail, 0, offset + completeLength, offset + length)
        tail[remaining] = 0x80.toByte()
        val bitLength = length.toLong() * 8L
        for (index in 0 until 8) tail[tail.lastIndex - index] = (bitLength ushr (8 * index)).toByte()
        compress(tail, 0, state, schedule)
        if (tail.size == 128) compress(tail, 64, state, schedule)
        return ByteArray(32).also { output ->
            for (index in state.indices) Bytes.putU32be(output, index * 4, state[index])
        }
    }

    /** 将任意字节转为小写十六进制；本函数不执行摘要。 / Encodes arbitrary bytes as lowercase hexadecimal without hashing them. */
    public fun hex(bytes: ByteArray): String = buildString {
        val digits = "0123456789abcdef"
        for (value in bytes) {
            val unsigned = value.toInt() and 0xff
            append(digits[unsigned ushr 4])
            append(digits[unsigned and 15])
        }
    }

    private fun compress(bytes: ByteArray, offset: Int, state: IntArray, schedule: IntArray) {
        for (index in 0 until 16) {
            val position = offset + index * 4
            schedule[index] = (Bytes.u8(bytes, position) shl 24) or
                (Bytes.u8(bytes, position + 1) shl 16) or
                (Bytes.u8(bytes, position + 2) shl 8) or Bytes.u8(bytes, position + 3)
        }
        for (index in 16 until 64) {
            val s0 = schedule[index - 15].rotateRight(7) xor schedule[index - 15].rotateRight(18) xor
                (schedule[index - 15] ushr 3)
            val s1 = schedule[index - 2].rotateRight(17) xor schedule[index - 2].rotateRight(19) xor
                (schedule[index - 2] ushr 10)
            schedule[index] = schedule[index - 16] + s0 + schedule[index - 7] + s1
        }
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (index in 0 until 64) {
            val sum1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val choice = (e and f) xor (e.inv() and g)
            val temporary1 = h + sum1 + choice + roundConstants[index] + schedule[index]
            val sum0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temporary2 = sum0 + majority
            h = g
            g = f
            f = e
            e = d + temporary1
            d = c
            c = b
            b = a
            a = temporary1 + temporary2
        }
        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }
}

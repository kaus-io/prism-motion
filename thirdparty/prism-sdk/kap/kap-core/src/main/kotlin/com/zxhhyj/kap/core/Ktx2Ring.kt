package com.zxhhyj.kap.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the header prefix for a ring / chunked texture array from the source KTX2.
 *
 * The runtime keeps only [ringSize] array layers resident. The prefix is the source
 * header (including DFD and key/value metadata) with `layerCount` and the level
 * length patched to the smaller ring; the caller appends the selected layers.
 *
 * 从源 KTX2 构造环形 / 分块纹理数组的头部前缀。运行时只常驻 [ringSize] 层；
 * 前缀沿用源文件的 DFD 与 KVD，只把 layerCount 和 level 长度改成环形大小，
 * 调用方随后追加选中的 layer 数据。
 */
public object Ktx2Ring {
    /**
     * @param sourcePrefix the first [Ktx2Info.levelOffset] bytes of the source file.
     *                     源文件前 [Ktx2Info.levelOffset] 字节。
     */
    public fun header(
        sourcePrefix: ByteArray,
        info: Ktx2Info,
        ringSize: Int,
    ): ByteArray {
        require(!info.isZstd) { "Zstd KTX2 must be decompressed before building a ring" }
        require(ringSize in 1..info.layerCount) {
            "ringSize $ringSize out of 1..${info.layerCount}"
        }
        val prefixLength = info.levelOffset
        require(prefixLength in 1..sourcePrefix.size.toLong()) {
            "source prefix is shorter than levelOffset=$prefixLength"
        }
        require(prefixLength <= Int.MAX_VALUE) { "levelOffset is too large" }
        val header = sourcePrefix.copyOf(prefixLength.toInt())
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(32, ringSize)
        val ringBytes = info.layerBytes.toLong() * ringSize
        buffer.putLong(80, info.levelOffset)
        buffer.putLong(88, ringBytes)
        buffer.putLong(96, ringBytes)
        return header
    }
}

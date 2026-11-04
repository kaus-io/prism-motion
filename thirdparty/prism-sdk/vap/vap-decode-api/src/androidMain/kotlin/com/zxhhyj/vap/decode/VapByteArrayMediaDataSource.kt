package com.zxhhyj.vap.decode

import android.media.MediaDataSource

/**
 * [MediaDataSource] over an in-memory MP4. Used by [MediaExtractor] /
 * [android.media.MediaMetadataRetriever] so `VapSource.Bytes` does not copy to disk.
 *
 * 内存 MP4 的 [MediaDataSource]。给 [MediaExtractor] / [android.media.MediaMetadataRetriever]
 * 用，避免 `VapSource.Bytes` 落到磁盘。
 */
public class VapByteArrayMediaDataSource(
    private val data: ByteArray,
) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0) return -1
        if (position >= data.size) return -1
        val start = position.toInt()
        val count = minOf(size, data.size - start)
        System.arraycopy(data, start, buffer, offset, count)
        return count
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {}
}

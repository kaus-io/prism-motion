package com.zxhhyj.vap.decode

import com.zxhhyj.vap.mp4.Mp4Source
import java.io.Closeable
import java.io.EOFException
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** 独占 channel 上的有界定位读，不修改共享 seek 位置。 / Bounded positional reads on an owned channel, without shared seeks. */
internal class VapFileChannelMp4Source(
    private val channel: FileChannel,
    private val base: Long = 0L,
    private val length: Long = channel.size(),
) : Mp4Source, Closeable {
    init {
        requireVapRegionBounds(base, length, channel.size())
    }

    override fun size(): Long = length

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= buffer.size && length <= buffer.size - offset)
        require(position >= 0L && position <= this.length && length.toLong() <= this.length - position) {
            "Read exceeds MP4 region"
        }
        val destination = ByteBuffer.wrap(buffer, offset, length)
        var cursor = base + position
        while (destination.hasRemaining()) {
            val count = channel.read(destination, cursor)
            if (count <= 0) throw EOFException("Unexpected EOF in MP4 region at $cursor")
            cursor += count
        }
    }

    override fun close() = channel.close()
}

/** 用减法校验边界，避免区间末尾溢出。 / Validates bounds using subtraction to avoid overflowing the region end. */
internal fun requireVapRegionBounds(offset: Long, length: Long, available: Long) {
    require(available >= 0L && offset >= 0L && length >= 0L && offset <= available && length <= available - offset) {
        "MP4 region offset=$offset length=$length exceeds source length=$available"
    }
}

/** 每次打开独占的只读文件句柄。 / Opens a fresh owned read-only file handle each time. */
internal fun openVapFileMp4Source(path: String, offset: Long = 0L, length: Long? = null): VapFileChannelMp4Source {
    val channel = FileInputStream(path).channel
    var retained = false
    try {
        return VapFileChannelMp4Source(channel, offset, length ?: channel.size()).also { retained = true }
    } finally {
        if (!retained) channel.close()
    }
}

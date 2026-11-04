package com.zxhhyj.vap.mp4

/**
 * Box type of the embedded poster: the packed frame-0 still (PNG), appended as a
 * top-level box after `mdat` by the vap tool. Appending keeps every `stco`/`co64`
 * media offset intact; players skip unknown boxes, so plain MP4 compatibility is
 * unaffected.
 *
 * 内嵌海报的 box 类型：打包第 0 帧静图（PNG），由 vap 工具作为顶层 box 追加在
 * `mdat` 之后。追加写法保证 `stco`/`co64` 的媒体偏移全部不变；标准播放器会跳过
 * 未知 box，因此不影响普通 MP4 兼容性。
 */
public const val VAP_POSTER_BOX_TYPE: String = "vapp"

/**
 * Random-access byte source used by the MP4 box parser.
 *
 * `size()` is the total number of bytes exposed; `readAt` must copy exactly
 * `length` bytes starting at `position` into the destination buffer. The
 * implementation is expected to be safe to call from any thread as long as the
 * underlying storage is not mutated concurrently.
 *
 * MP4 盒解析器所用的随机访问字节源。
 *
 * `size()` 是可见的总字节数；`readAt` 必须从 `position` 起精确拷贝 `length`
 * 字节到目标缓冲。底层存储不被并发改写时，实现应可在任意线程调用。
 */
public interface Mp4Source {
    public fun size(): Long
    public fun readAt(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)
}

/**
 * A single ISO BMFF (MP4) box, including its header layout.
 *
 * `size` includes the header bytes. When `extendsToEof` is true the box spans to
 * the end of the container (the size field was 0).
 *
 * 单个 ISO BMFF (MP4) 盒，包含其头部的布局信息。
 *
 * `size` 含头部字节。`extendsToEof` 为 true 时盒延伸到容器末尾（size 字段为 0）。
 */
public data class Mp4Box(
    val offset: Long,
    val size: Long,
    val type: String,
    val headerSize: Int,
    val extendsToEof: Boolean = false,
) {
    val payloadOffset: Long get() = offset + headerSize
    val payloadSize: Long get() = size - headerSize
}

public object Mp4Sources {
    public fun bytes(data: ByteArray): Mp4Source = ByteArrayMp4Source(data)
}

/**
 * Minimal ISO BMFF box scanner that walks top-level boxes.
 *
 * Supports 32-bit and 64-bit (`size==1`) headers and the `size==0` "to EOF"
 * convention. The parser is intentionally stateless beyond the input source so
 * the same instance can be reused across reads.
 *
 * 极简 ISO BMFF 盒扫描器，仅遍历顶层盒。
 *
 * 支持 32 位与 64 位（`size==1`）头，以及 `size==0` 的「直到 EOF」约定。
 * 除输入源外有意保持无状态，同一实例可跨多次读取复用。
 */
public object Mp4BoxParser {
    public fun parseTopLevel(source: Mp4Source, strict: Boolean = true): List<Mp4Box> {
        val fileSize = source.size()
        val boxes = ArrayList<Mp4Box>()
        if (fileSize == 0L) return boxes
        if (fileSize < 8L) {
            if (strict) error("mp4 too small: $fileSize")
            return boxes
        }
        var offset = 0L
        val header = ByteArray(16)
        while (offset + 8 <= fileSize) {
            val box = readBoxAt(source, offset, fileSize, header, strict) ?: break
            boxes += box
            offset = box.offset + box.size
            if (box.extendsToEof || offset >= fileSize) break
        }
        if (strict && offset != fileSize) {
            error("trailing bytes after last box: $offset/$fileSize")
        }
        return boxes
    }

    public fun findPayload(source: Mp4Source, type: String): ByteArray? {
        for (box in parseTopLevel(source, strict = false)) {
            if (box.type != type) continue
            val payloadSize = box.payloadSize
            if (payloadSize < 0L || payloadSize > Int.MAX_VALUE) return null
            val payload = ByteArray(payloadSize.toInt())
            if (payload.isNotEmpty()) {
                source.readAt(box.payloadOffset, payload, 0, payload.size)
            }
            return payload
        }
        return null
    }

    /**
     * Forward scan of top-level boxes from a stream. Stops at [type] or `mdat`
     * so a Coil/asset peek does not buffer the media payload.
     *
     * 从流正向扫描顶层盒。命中 [type] 或 `mdat` 即停，避免 Coil / assets peek 把媒体 payload 整段读进内存。
     */
    public fun findPayloadFromStream(input: java.io.InputStream, type: String): ByteArray? {
        val header = ByteArray(16)
        while (true) {
            if (!readFully(input, header, 0, 8)) return null
            val size32 = readU32(header, 0)
            val boxType = header.decodeToString(4, 8)
            val headerSize: Int
            val boxSize: Long
            when {
                size32 == 1L -> {
                    if (!readFully(input, header, 8, 8)) return null
                    val size64 = readU64(header, 8)
                    if (size64 < 16L) return null
                    headerSize = 16
                    boxSize = size64
                }
                size32 == 0L -> return null
                else -> {
                    if (size32 < 8L) return null
                    headerSize = 8
                    boxSize = size32
                }
            }
            val payloadSize = boxSize - headerSize
            if (payloadSize < 0L || payloadSize > Int.MAX_VALUE) return null
            if (boxType == type) {
                val payload = ByteArray(payloadSize.toInt())
                if (payload.isNotEmpty() && !readFully(input, payload, 0, payload.size)) return null
                return payload
            }
            if (boxType == "mdat") return null
            if (!skipFully(input, payloadSize)) return null
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray, offset: Int, length: Int): Boolean {
        var done = 0
        while (done < length) {
            val n = input.read(buffer, offset + done, length - done)
            if (n < 0) return false
            done += n
        }
        return true
    }

    private fun skipFully(input: java.io.InputStream, count: Long): Boolean {
        var left = count
        val buf = ByteArray(8192)
        while (left > 0L) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) return false
            left -= n.toLong()
        }
        return true
    }

    // Decodes one box header starting at `offset`; returns null on a clean EOF
    // only when `strict == false`, otherwise raises on malformed headers.
    // 从 `offset` 解码一个盒头；仅在 `strict == false` 时对干净 EOF 返回 null，
    // 否则畸形头会抛错。
    private fun readBoxAt(
        source: Mp4Source,
        offset: Long,
        fileSize: Long,
        header: ByteArray,
        strict: Boolean,
    ): Mp4Box? {
        source.readAt(offset, header, 0, 8)
        val size32 = readU32(header, 0)
        val type = header.decodeToString(4, 8)
        val headerSize: Int
        val boxSize: Long
        val extendsToEof: Boolean
        when {
            size32 == 1L -> {
                if (offset + 16 > fileSize) {
                    if (strict) error("truncated 64-bit box at $offset")
                    return null
                }
                source.readAt(offset + 8, header, 8, 8)
                val size64 = readU64(header, 8)
                if (size64 < 16L) {
                    if (strict) error("invalid 64-bit box size $size64 at $offset")
                    return null
                }
                headerSize = 16
                boxSize = size64
                extendsToEof = false
            }

            size32 == 0L -> {
                headerSize = 8
                boxSize = fileSize - offset
                extendsToEof = true
            }

            else -> {
                if (size32 < 8L) {
                    if (strict) error("invalid box size $size32 at $offset")
                    return null
                }
                headerSize = 8
                boxSize = size32
                extendsToEof = false
            }
        }
        if (offset + boxSize > fileSize) {
            if (strict) {
                error("box overflows file: type=$type offset=$offset size=$boxSize fileSize=$fileSize")
            }
            return null
        }
        return Mp4Box(
            offset = offset,
            size = boxSize,
            type = type,
            headerSize = headerSize,
            extendsToEof = extendsToEof,
        )
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toInt() and 0xff).toLong() shl 24) or
                ((bytes[offset + 1].toInt() and 0xff).toLong() shl 16) or
                ((bytes[offset + 2].toInt() and 0xff).toLong() shl 8) or
                (bytes[offset + 3].toInt() and 0xff).toLong()
    }

    private fun readU64(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (bytes[offset + i].toInt() and 0xff).toLong()
        }
        return v
    }
}

private class ByteArrayMp4Source(private val bytes: ByteArray) : Mp4Source {
    override fun size(): Long = bytes.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        require(position >= 0 && length >= 0)
        require(position + length <= bytes.size)
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + length)
    }
}

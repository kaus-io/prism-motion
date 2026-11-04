package com.zxhhyj.kap.android

/** 有界、零拷贝的逻辑 KTX2 视图；关闭时释放底层读取器。 / Bounded zero-copy KTX2 view owning its reader. */
internal class RegionKtxReader(
    private val reader: KtxByteReader,
    private val offset: Long,
    override val size: Long,
) : KtxByteReader {
    init {
        require(size > 0L) { "KTX2 region must not be empty" }
        requireKtxRange(reader.size, offset, size)
    }

    override fun readAt(offset: Long, destination: ByteArray, destinationOffset: Int, length: Int) {
        requireKtxRead(size, offset, destination, destinationOffset, length)
        reader.readAt(this.offset + offset, destination, destinationOffset, length)
    }

    override fun openNativeInput(offset: Long, length: Long): KtxNativeInput {
        requireKtxRange(size, offset, length)
        return reader.openNativeInput(this.offset + offset, length)
    }

    override fun close() = reader.close()
}

/** 用减法校验范围，避免偏移与长度相加溢出。 / Checks ranges by subtraction without overflowing their sum. */
internal fun requireKtxRange(size: Long, offset: Long, length: Long) {
    require(size >= 0L && offset >= 0L && offset <= size && length >= 0L && length <= size - offset) {
        "KTX2 range outside input: offset=$offset length=$length size=$size"
    }
}

/** 同时校验逻辑区域与目标数组边界。 / Checks both the logical input and destination array bounds. */
internal fun requireKtxRead(size: Long, offset: Long, destination: ByteArray, destinationOffset: Int, length: Int) {
    requireKtxRange(size, offset, length.toLong())
    require(destinationOffset >= 0 && destinationOffset <= destination.size && length <= destination.size - destinationOffset) {
        "KTX2 read outside destination"
    }
}

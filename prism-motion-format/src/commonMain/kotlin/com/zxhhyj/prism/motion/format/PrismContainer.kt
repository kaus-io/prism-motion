package com.zxhhyj.prism.motion.format

/**
 * 无 IO 的小端 .prism v1 容器；无效输入抛出 IllegalArgumentException。 / IO-free little-endian .prism v1 codec; invalid input throws IllegalArgumentException.
 * 原生载荷保持完整且不在本层解析。 / Native payload bytes remain complete and are not interpreted here.
 */
public object PrismContainer {
    /** 固定头长度。 / Fixed header length. */
    public const val HEADER_SIZE: Int = 64

    /** 头、元数据和单条索引的总长度。 / Combined header, metadata and single-entry index length. */
    public const val PREFIX_SIZE: Int = 192

    /** 零填充之后的原生载荷起点。 / Native payload start after zero alignment padding. */
    public const val PAYLOAD_OFFSET: Int = 4096

    private val magic = byteArrayOf(0x50, 0x52, 0x49, 0x53, 0x4d, 0, 0, 0)

    /** 只识别完整的八字节魔数，不证明容器有效。 / Recognizes only the full eight-byte magic, not container validity. */
    public fun isPrism(bytes: ByteArray): Boolean =
        bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }

    /**
     * 只读取前 192 字节，并核对外部提供的真实文件长度。 / Reads only the first 192 bytes and checks the supplied actual file size.
     * 不读取对齐填充或载荷；不验证载荷 SHA-256。 / Does not read alignment padding or payload, nor verify payload SHA-256.
     */
    public fun readPrefix(prefix: ByteArray, totalSize: Long): PrismAssetDescriptor {
        require(prefix.size >= PREFIX_SIZE) { "Truncated Prism prefix" }
        require(isPrism(prefix)) { "Invalid Prism magic" }
        require(u16(prefix, 8) == 1 && u16(prefix, 10) == 0) { "Unsupported Prism version" }
        require(Bytes.u32le(prefix, 12) == HEADER_SIZE.toLong()) { "Invalid Prism header size" }
        val fileSize = nonnegativeU64(prefix, 16, "file size")
        require(fileSize == totalSize) { "Prism file size does not match actual length" }
        require(nonnegativeU64(prefix, 24, "metadata offset") == 64L) { "Invalid Prism metadata offset" }
        require(nonnegativeU64(prefix, 32, "metadata length") == 64L) { "Invalid Prism metadata length" }
        require(nonnegativeU64(prefix, 40, "index offset") == 128L) { "Invalid Prism index offset" }
        require(Bytes.u32le(prefix, 48) == 1L) { "Prism v1 requires exactly one payload" }
        require(Bytes.u32le(prefix, 52) == 0L) { "Unsupported Prism flags" }
        requireZero(prefix, 60, 64)
        require(Bytes.u32le(prefix, 56) == prefixCrc32(prefix)) { "Prism prefix CRC32 mismatch" }

        val metadata = PrismMetadata(
            width = positiveInt(prefix, 68, "width"),
            height = positiveInt(prefix, 72, "height"),
            frameCount = positiveInt(prefix, 76, "frame count"),
            fpsNumerator = positiveInt(prefix, 80, "FPS numerator"),
            fpsDenominator = positiveInt(prefix, 84, "FPS denominator"),
            alphaMode = when (Bytes.u32le(prefix, 88)) {
                0L -> PrismAlphaMode.Opaque
                1L -> PrismAlphaMode.Straight
                2L -> PrismAlphaMode.Premultiplied
                else -> throw IllegalArgumentException("Unsupported Prism alpha mode")
            },
            colorSpace = when (Bytes.u32le(prefix, 92)) {
                1L -> PrismColorSpace.Srgb
                else -> throw IllegalArgumentException("Unsupported Prism color space")
            },
            schemaVersion = positiveInt(prefix, 64, "metadata schema"),
        )
        requireZero(prefix, 96, 128)
        val kind = when (Bytes.u32le(prefix, 128)) {
            1L -> PrismPayloadKind.Vap
            2L -> PrismPayloadKind.Ktx2
            else -> throw IllegalArgumentException("Unsupported Prism payload kind")
        }
        requireZero(prefix, 132, 136)
        val payloadOffset = nonnegativeU64(prefix, 136, "payload offset")
        require(payloadOffset == PAYLOAD_OFFSET.toLong()) { "Invalid Prism payload offset" }
        val payloadLength = nonnegativeU64(prefix, 144, "payload length")
        require(payloadLength > 0) { "Prism payload must not be empty" }
        require(payloadLength <= Long.MAX_VALUE - payloadOffset) { "Prism payload range overflows" }
        require(payloadOffset + payloadLength == fileSize) { "Prism payload length does not match file size" }
        requireZero(prefix, 184, PREFIX_SIZE)
        return PrismAssetDescriptor(
            metadata = metadata,
            kind = kind,
            payloadOffset = payloadOffset,
            payloadLength = payloadLength,
            payloadSha256 = prefix.copyOfRange(152, 184),
            fileSize = fileSize,
        )
    }

    /**
     * 确定性地封装完整原生载荷；输出受 ByteArray 的 Int 大小限制。 / Deterministically wraps a full native payload within ByteArray's Int size limit.
     * 自动生成前缀 CRC32、载荷 SHA-256 和零填充。 / Generates prefix CRC32, payload SHA-256 and zero padding.
     */
    public fun write(metadata: PrismMetadata, kind: PrismPayloadKind, payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "Prism payload must not be empty" }
        require(payload.size <= Int.MAX_VALUE - PAYLOAD_OFFSET) { "Prism output exceeds ByteArray size limit" }
        val output = ByteArray(PAYLOAD_OFFSET + payload.size)
        magic.copyInto(output)
        output[8] = 1
        Bytes.putU32le(output, 12, HEADER_SIZE)
        Bytes.putU64le(output, 16, output.size.toLong())
        Bytes.putU64le(output, 24, 64L)
        Bytes.putU64le(output, 32, 64L)
        Bytes.putU64le(output, 40, 128L)
        Bytes.putU32le(output, 48, 1)
        Bytes.putU32le(output, 64, metadata.schemaVersion)
        Bytes.putU32le(output, 68, metadata.width)
        Bytes.putU32le(output, 72, metadata.height)
        Bytes.putU32le(output, 76, metadata.frameCount)
        Bytes.putU32le(output, 80, metadata.fpsNumerator)
        Bytes.putU32le(output, 84, metadata.fpsDenominator)
        Bytes.putU32le(output, 88, metadata.alphaMode.code)
        Bytes.putU32le(output, 92, metadata.colorSpace.code)
        Bytes.putU32le(output, 128, kind.code)
        Bytes.putU64le(output, 136, PAYLOAD_OFFSET.toLong())
        Bytes.putU64le(output, 144, payload.size.toLong())
        PrismSha256.digest(payload).copyInto(output, 152)
        Bytes.putU32le(output, 56, prefixCrc32(output).toInt())
        payload.copyInto(output, PAYLOAD_OFFSET)
        return output
    }

    /**
     * 读取完整容器并检查零填充；仅在显式请求时验证载荷摘要。 / Reads a full container and zero padding; verifies payload digest only when requested.
     * 不复制载荷。 / Does not copy the payload.
     */
    public fun read(bytes: ByteArray, verifyPayload: Boolean = false): PrismAssetDescriptor {
        val descriptor = readPrefix(bytes, bytes.size.toLong())
        requireZero(bytes, PREFIX_SIZE, PAYLOAD_OFFSET)
        if (verifyPayload) {
            val actual = PrismSha256.digest(bytes, PAYLOAD_OFFSET, descriptor.payloadLength.toInt())
            require(actual.contentEquals(descriptor.payloadSha256)) { "Prism payload SHA-256 mismatch" }
        }
        return descriptor
    }

    /** 验证完整容器，包括载荷 SHA-256，返回描述信息。 / Validates the full container including payload SHA-256 and returns its descriptor. */
    public fun verify(bytes: ByteArray): PrismAssetDescriptor = read(bytes, verifyPayload = true)

    /** 提取完整原生载荷副本，可显式启用摘要验证。 / Copies out the complete native payload, with optional explicit digest verification. */
    public fun extractPayload(bytes: ByteArray, verifyPayload: Boolean = false): ByteArray {
        read(bytes, verifyPayload)
        return bytes.copyOfRange(PAYLOAD_OFFSET, bytes.size)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        Bytes.u8(bytes, offset) or (Bytes.u8(bytes, offset + 1) shl 8)

    private fun nonnegativeU64(bytes: ByteArray, offset: Int, field: String): Long {
        val value = Bytes.u64le(bytes, offset)
        require(value >= 0) { "Prism $field exceeds the signed Long range" }
        return value
    }

    private fun positiveInt(bytes: ByteArray, offset: Int, field: String): Int {
        val value = Bytes.u32le(bytes, offset)
        require(value in 1L..Int.MAX_VALUE.toLong()) { "Prism $field must be a positive Int value" }
        return value.toInt()
    }

    private fun requireZero(bytes: ByteArray, start: Int, end: Int) {
        for (index in start until end) require(bytes[index] == 0.toByte()) {
            "Nonzero Prism reserved byte or padding at $index"
        }
    }

    private fun prefixCrc32(bytes: ByteArray): Long {
        var crc = -1
        for (index in 0 until PREFIX_SIZE) {
            val value = if (index in 56..59) 0 else Bytes.u8(bytes, index)
            crc = crc xor value
            repeat(8) { crc = (crc ushr 1) xor (if (crc and 1 != 0) 0xedb88320.toInt() else 0) }
        }
        return crc.inv().toLong() and 0xffffffffL
    }
}

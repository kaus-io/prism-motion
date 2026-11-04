package com.zxhhyj.prism.motion.format

/** 原生载荷的类型，不改变载荷内部字节序。 / Native payload kind; its internal byte order is unchanged. */
public enum class PrismPayloadKind(public val code: Int) {
    Vap(1),
    Ktx2(2),
}

/** 像素的透明度语义。 / Alpha interpretation of the pixels. */
public enum class PrismAlphaMode(public val code: Int) {
    Opaque(0),
    Straight(1),
    Premultiplied(2),
}

/** 容器支持的色彩空间。 / Color spaces supported by the container. */
public enum class PrismColorSpace(public val code: Int) {
    Srgb(1),
}

/**
 * v1 元数据：正数限定于 Int 范围，帧率保留为有理数。 / v1 metadata with positive Int fields and rational FPS.
 * 时长向下取整到毫秒；超出 Long 范围的时长被拒绝。 / Duration is floored to milliseconds and must fit in Long.
 */
public data class PrismMetadata(
    public val width: Int,
    public val height: Int,
    public val frameCount: Int,
    public val fpsNumerator: Int,
    public val fpsDenominator: Int,
    public val alphaMode: PrismAlphaMode = PrismAlphaMode.Straight,
    public val colorSpace: PrismColorSpace = PrismColorSpace.Srgb,
    public val schemaVersion: Int = 1,
) {
    init {
        require(schemaVersion == 1) { "Unsupported Prism metadata schema: $schemaVersion" }
        require(width > 0 && height > 0) { "Prism dimensions must be positive Int values" }
        require(frameCount > 0) { "Prism frame count must be a positive Int value" }
        require(fpsNumerator > 0 && fpsDenominator > 0) { "Prism FPS terms must be positive Int values" }
    }

    /** 帧数与有理帧率推导的毫秒时长。 / Millisecond duration derived from frame count and rational FPS. */
    public val durationMs: Long = durationMillis(frameCount, fpsNumerator, fpsDenominator)
}

/**
 * 仅含容器描述，不持有原生载荷；SHA-256 数组按内容比较。 / Payload-free descriptor with content-based SHA-256 equality.
 * 摘要数组归调用者所有，不应修改。 / The digest array belongs to the caller and should not be mutated.
 */
public data class PrismAssetDescriptor(
    public val metadata: PrismMetadata,
    public val kind: PrismPayloadKind,
    public val payloadOffset: Long,
    public val payloadLength: Long,
    public val payloadSha256: ByteArray,
    public val fileSize: Long,
) {
    init {
        require(payloadSha256.size == 32) { "Prism SHA-256 must contain 32 bytes" }
    }

    /** 小写十六进制载荷摘要。 / Lowercase hexadecimal payload digest. */
    public val payloadSha256Hex: String get() = PrismSha256.hex(payloadSha256)

    override fun equals(other: Any?): Boolean = this === other ||
        other is PrismAssetDescriptor && metadata == other.metadata && kind == other.kind &&
        payloadOffset == other.payloadOffset && payloadLength == other.payloadLength &&
        payloadSha256.contentEquals(other.payloadSha256) && fileSize == other.fileSize

    override fun hashCode(): Int {
        var result = metadata.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + payloadOffset.hashCode()
        result = 31 * result + payloadLength.hashCode()
        result = 31 * result + payloadSha256.contentHashCode()
        return 31 * result + fileSize.hashCode()
    }
}

private fun durationMillis(frameCount: Int, numerator: Int, denominator: Int): Long {
    val scaledFrames = frameCount.toLong() * denominator.toLong()
    val wholeSeconds = scaledFrames / numerator
    val fractionalMillis = (scaledFrames % numerator) * 1_000L / numerator
    require(wholeSeconds <= (Long.MAX_VALUE - fractionalMillis) / 1_000L) {
        "Prism duration exceeds the Long millisecond range"
    }
    return wholeSeconds * 1_000L + fractionalMillis
}

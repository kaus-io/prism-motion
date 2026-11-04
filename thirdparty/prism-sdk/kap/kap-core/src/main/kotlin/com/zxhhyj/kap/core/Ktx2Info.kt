package com.zxhhyj.kap.core

/**
 * Parsed KTX2 metadata for a one-level, one-face animation texture array.
 *
 * 解析后的 KTX2 元数据：一层 mip、单面、每个 array layer 是一帧。
 */
public data class Ktx2Info(
    public val vkFormat: Int,
    public val format: KtxFormat?,
    public val width: Int,
    public val height: Int,
    public val layerCount: Int,
    public val levelCount: Int,
    public val supercompression: Int,
    public val levelOffset: Long,
    /** Stored bytes in the file (compressed when [isZstd]). / 文件中的实际字节数（Zstd 时为压缩后大小）。 */
    public val levelLength: Long,
    /** Uncompressed level bytes. / 解压后的 level 字节数。 */
    public val uncompressedLength: Long,
    public val layerBytes: Int,
    public val keyValues: Map<String, String>,
) {
    /** True when the level uses KTX2 Zstd supercompression. / 是否使用 KTX2 Zstd 超压缩。 */
    public val isZstd: Boolean get() = supercompression == SUPERCOMPRESSION_ZSTD

    /** Playback rate embedded by the packer; defaults to 30. / 打包时写入的帧率，默认 30。 */
    public val fps: Int
        get() = keyValues[KEY_FPS]?.toIntOrNull()?.takeIf { it > 0 } ?: 30

    /** Whether the clip loops by default. / 默认是否循环。 */
    public val loop: Boolean
        get() = when (keyValues[KEY_LOOP]?.lowercase()) {
            "false", "0", "no" -> false
            else -> true
        }

    /** Whether RGB was premultiplied by alpha before ASTC/ETC2 encoding. / RGB 是否已预乘 alpha。 */
    public val premultiplied: Boolean
        get() = when (keyValues[KEY_PREMULTIPLIED]?.lowercase()) {
            "true", "1", "yes" -> true
            else -> false
        }

    /**
     * Absolute byte offset of [layer] inside an uncompressed KTX2 file.
     * Zstd levels must be decompressed as a whole; random access is not available.
     *
     * 未压缩 KTX2 中 [layer] 的绝对字节偏移。Zstd 需要整段解压，不能按层随机访问。
     */
    public fun layerOffset(layer: Int): Long {
        require(!isZstd) { "Zstd-compressed KTX2 has no per-layer file offset" }
        require(layer in 0 until layerCount) { "layer $layer out of 0..${layerCount - 1}" }
        return levelOffset + layer.toLong() * layerBytes
    }

    /** Absolute byte offset of [layer] inside the decompressed level. / 解压后 level 中该层的偏移。 */
    public fun decodedLayerOffset(layer: Int): Long {
        require(layer in 0 until layerCount) { "layer $layer out of 0..${layerCount - 1}" }
        return layer.toLong() * layerBytes
    }

    /** A short human-readable summary for CLI output. / CLI 输出用的简短摘要。 */
    public fun summary(): String = buildString {
        append("${width}x$height")
        append(" layers=$layerCount")
        append(" format=${format?.displayName ?: "vk=$vkFormat"}")
        append(" fps=$fps")
        append(" layerBytes=$layerBytes")
        append(" stored=${levelLength}B")
        if (isZstd) append(" decoded=${uncompressedLength}B zstd=true")
        append(" loop=$loop")
        append(" premultiplied=$premultiplied")
    }

    public companion object {
        public const val SUPERCOMPRESSION_NONE: Int = 0
        public const val SUPERCOMPRESSION_ZSTD: Int = 2

        // Avoid the reserved KTX/ktx key prefix; these are plain application keys.
        // 避免 KTX/ktx 保留前缀；这些是普通应用键。
        public const val KEY_FPS: String = "anim.fps"
        public const val KEY_LOOP: String = "anim.loop"
        public const val KEY_PREMULTIPLIED: String = "anim.premultiplied"
        public const val KEY_VERSION: String = "anim.version"
    }
}

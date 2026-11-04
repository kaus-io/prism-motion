package com.zxhhyj.kap.core

/**
 * Compressed / uncompressed Vulkan formats supported by the KTX2 animation runtime.
 *
 * 运行时支持的 Vulkan 纹理格式。`vkFormat` 与 KTX2 头中的数值一致。
 */
public enum class KtxFormat(
    public val vkFormat: Int,
    public val blockWidth: Int,
    public val blockHeight: Int,
    public val blockBytes: Int,
    public val displayName: String,
) {
    ASTC_4X4(157, 4, 4, 16, "ASTC 4x4"),
    ASTC_6X6(165, 6, 6, 16, "ASTC 6x6"),
    ASTC_8X8(171, 8, 8, 16, "ASTC 8x8"),
    ASTC_10X10(179, 10, 10, 16, "ASTC 10x10"),
    ASTC_12X12(183, 12, 12, 16, "ASTC 12x12"),
    ETC2_RGBA8(151, 4, 4, 16, "ETC2 RGBA8"),
    RGBA8(37, 1, 1, 4, "RGBA8");

    /** Byte size of one array layer for [width] x [height]. / 单层字节数。 */
    public fun layerSize(width: Int, height: Int): Int {
        require(width > 0 && height > 0) { "width/height must be positive" }
        val blocksX = (width + blockWidth - 1) / blockWidth
        val blocksY = (height + blockHeight - 1) / blockHeight
        return blocksX * blocksY * blockBytes
    }

    public companion object {
        /** Resolve a Vulkan format number from a KTX2 header. / 从 KTX2 头解析格式。 */
        public fun fromVkFormat(vkFormat: Int): KtxFormat? =
            entries.firstOrNull { it.vkFormat == vkFormat }
    }
}

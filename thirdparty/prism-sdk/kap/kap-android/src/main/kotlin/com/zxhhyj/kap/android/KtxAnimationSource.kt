package com.zxhhyj.kap.android

/**
 * Where the KTX2 animation lives.
 *
 * `Asset` paths are relative to `assets/` and must be stored uncompressed
 * (`androidResources.noCompress += "ktx2"`) when streaming mode is used.
 *
 * KTX2 动画的来源。`Asset` 路径相对 `assets/`；使用流式模式时必须在打包配置里
 * 对 `ktx2` 关闭压缩，否则无法按偏移读取。
 */
public sealed interface KtxAnimationSource {
    public data class Asset(public val path: String) : KtxAnimationSource

    public data class File(public val path: String) : KtxAnimationSource

    /**
     * 文件或未压缩 asset 内的完整 KTX2 区域；偏移相对来源，长度在每次打开时校验。
     * A complete KTX2 region in a file or uncompressed asset; bounds are checked on every open.
     *
     * 不持有描述符，不允许嵌套区域；asset 的外层文件扩展名也必须关闭 APK 压缩。
     * Owns no descriptor and cannot be nested; disable APK compression for the outer asset extension.
     */
    public data class Region(
        public val source: KtxAnimationSource,
        public val offset: Long,
        public val length: Long,
    ) : KtxAnimationSource {
        init {
            require(source is Asset || source is File) { "Region source must be an Asset or File" }
            require(offset >= 0L) { "Region offset must be non-negative" }
            require(length > 0L && length <= Long.MAX_VALUE - offset) { "Invalid Region length" }
        }
    }
}

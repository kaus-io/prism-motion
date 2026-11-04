package com.zxhhyj.vap.player

/**
 * Runtime view of a parsed VAP clip header.
 *
 * `width`/`height` describe the composited output canvas; `videoWidth` /
 * `videoHeight` describe the underlying encoded MP4 dimensions which may differ
 * when alpha/RGB tracks are cropped. `hasAlpha` indicates whether an alpha
 * track is present (older v2 clips may omit it).
 *
 * 已解析 VAP 片段头部的运行期模型。
 *
 * `width`/`height` 是合成后的输出画布；`videoWidth`/`videoHeight` 是底层编码
 * MP4 尺寸，裁切 alpha/RGB 轨时可能不同。`hasAlpha` 表示是否有 alpha 轨
 * （较旧的 v2 片段可能没有）。
 */
public data class VapConfig(
    val version: Int,
    val totalFrames: Int,
    val width: Int,
    val height: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    val fps: Int,
    val alphaFrame: VapRect,
    val rgbFrame: VapRect,
    val hasAlpha: Boolean = true,
)

/**
 * Integer rectangle in pixel space (origin top-left, both width/height in px).
 *
 * 像素空间中的整数矩形（原点在左上角，宽高单位均为像素）。
 */
public data class VapRect(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

/**
 * Output canvas size in pixels; consumers should treat both fields as >= 1.
 *
 * 输出画布尺寸（像素），使用方应将两个字段视为不小于 1。
 */
public data class VapSize(
    val width: Int,
    val height: Int,
)

/**
 * Source kind passed to a VAP decoder.
 *
 * Filesystem path (decoder-managed I/O), Android `assets/`, or an in-memory
 * buffer. Lifecycle rules differ, so they are a sealed hierarchy.
 *
 * 传递给 VAP 解码器的源类型。
 *
 * 文件系统路径（解码器管理 I/O）、Android `assets/`，或内存缓冲。
 * 生命周期规则不同，因此用密封层次建模。
 */
public sealed class VapSource {
    public data class AbsolutePath(val path: String) : VapSource()

    /**
     * Android `assets/` path (e.g. `raw/hanna_home.mp4`). Opened via
     * `AssetFileDescriptor` — no copy to disk. Ignored on JVM.
     *
     * Android `assets/` 路径（例如 `raw/hanna_home.mp4`）。通过 `AssetFileDescriptor`
     * 打开，不拷到磁盘。JVM 上不可用。
     */
    public data class Asset(val assetPath: String) : VapSource()

    /**
     * 文件或未压缩 asset 内的只读 MP4；[offset] 从该源起点计字节，[length] 是可见字节数。
     * 仅接受 [AbsolutePath] / [Asset]，打开时校验实际边界；不复制媒体，asset 区间仅 Android 可用。
     * / Read-only MP4 bytes at [offset] from the source origin, exposing exactly [length] bytes.
     * Only [AbsolutePath] / [Asset] bases are accepted; backing bounds are checked on open without copying media.
     * Asset regions are Android-only.
     */
    public data class Region(val source: VapSource, val offset: Long, val length: Long) : VapSource() {
        init {
            require(source is AbsolutePath || source is Asset) { "Region requires an AbsolutePath or Asset source" }
            require(offset >= 0L) { "Region offset must be non-negative" }
            require(length > 0L) { "Region length must be positive" }
            require(offset <= Long.MAX_VALUE - length) { "Region end overflows Long" }
        }
    }

    /**
     * In-memory MP4 bytes. The array must not be mutated after construction;
     * create a new [Bytes] instance to replace the buffer.
     *
     * 内存中的 MP4 字节。构造后不得再修改底层数组；如需替换缓冲，请创建新的 [Bytes] 实例。
     */
    public class Bytes private constructor(
        public val data: ByteArray,
    ) : VapSource() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Bytes
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()

        override fun toString(): String = "Bytes(size=${data.size})"

        public companion object {
            public operator fun invoke(data: ByteArray): Bytes = Bytes(data.copyOf())

            /** Takes ownership of [data]; caller must not mutate it afterwards. 接管 [data] 所有权；调用方之后不得再改写。 */
            public fun wrap(data: ByteArray): Bytes = Bytes(data)
        }
    }
}

package com.zxhhyj.kap.android

/**
 * How the texture array is kept in VRAM.
 *
 * - [Static] uploads every layer once. Lowest CPU, highest VRAM.
 * - [Streaming] keeps only [Streaming.ringSize] layers and uploads one layer per
 *   presented frame. Zstd levels are streamed into an internal cache file on first
 *   use; neither the whole compressed nor decoded level stays in the Java heap.
 *   Lower VRAM, slightly higher CPU / I/O.
 *
 * 纹理数组的显存策略：静态一次上传全部层；流式只保留环形层数并逐帧上传。
 */
public sealed interface KtxPlaybackMode {
    public data object Static : KtxPlaybackMode

    public data class Streaming(public val ringSize: Int = 32) : KtxPlaybackMode {
        init {
            require(ringSize > 0) { "ringSize must be positive" }
        }
    }
}

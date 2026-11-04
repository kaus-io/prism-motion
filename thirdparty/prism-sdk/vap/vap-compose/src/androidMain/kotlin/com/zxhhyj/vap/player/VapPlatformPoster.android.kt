package com.zxhhyj.vap.player

import com.zxhhyj.vap.decode.VapPosterEntry
import com.zxhhyj.vap.decode.decodeVapPackedPixels
import com.zxhhyj.vap.decode.decodeVapPoster

internal actual class VapPlatformPoster actual constructor() {
    private val lock = Any()
    private var entry: VapPosterEntry? = null

    actual val isReady: Boolean
        get() = synchronized(lock) { entry?.isUsable() == true }

    actual fun warm(source: VapSource, config: VapConfig) {
        synchronized(lock) {
            if (entry?.isUsable() == true) return
            entry = decodeVapPoster(source, config)
        }
    }

    fun snapshot(): VapPosterEntry? = synchronized(lock) { entry?.takeIf { it.isUsable() } }

    fun ensurePackedPixels(source: VapSource, config: VapConfig): VapPosterEntry? {
        synchronized(lock) {
            val current = entry?.takeIf { it.isUsable() }
            val next = when {
                current == null -> decodeVapPoster(source, config)?.ensurePackedPixels(source, config)
                else -> current.ensurePackedPixels(source, config)
            }
            if (next != null) entry = next
            return next
        }
    }

    fun releasePackedPixels() {
        synchronized(lock) {
            val current = entry ?: return
            if (current.packedPixels == null) return
            entry = current.copy(packedPixels = null)
        }
    }

    /**
     * Drops the whole poster (composited bitmap + packed pixels). Used after the
     * Surface path has handed the packed pixels to the decoder session: the session
     * owns them until the first codec frame, and the still-only path can re-warm the
     * poster on demand.
     *
     * 丢弃整份海报（合成位图 + 打包像素）。Surface 路径把打包像素交给解码会话后使用：
     * 会话在首个 codec 帧前持有像素；静帧档需要时会重新预热海报。
     */
    fun releaseAll() {
        synchronized(lock) {
            entry = null
        }
    }

    /**
     * Returns packed frame-0 pixels for the Vulkan opening texture without composing the
     * cropped poster bitmap. Prefers an already-cached entry, otherwise decodes the packed
     * frame directly and recycles the temporary Bitmap.
     *
     * 返回供 Vulkan opening 纹理使用的打包第 0 帧像素，不合成裁切海报位图。
     * 优先复用已缓存 entry，否则直接解码打包帧并回收临时 Bitmap。
     */
    fun loadPackedPixels(source: VapSource, config: VapConfig): IntArray? {
        synchronized(lock) {
            entry?.takeIf { it.isUsable() }?.packedPixels?.let { return it }
            return decodeVapPackedPixels(source, config)
        }
    }
}

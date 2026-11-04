package com.zxhhyj.kap.compose

import androidx.compose.ui.graphics.ImageBitmap
import com.zxhhyj.kap.android.KtxAnimationSource

/**
 * 最近一帧的内存缓存，语义对齐 Coil 的 `MemoryCache`：
 * 以请求（KTX 资源 + 尺寸）为 key 保存**已解码像素**，再次显示时无需重新解码/上传。
 *
 * 只用于“上一帧占位”，不负责播放；真正的输出仍由各自 Surface 准备。
 * 与 Coil 一样按字节数做 LRU 淘汰，访问即刷新顺序。
 */
public class KtxFrameCache(
    private val maxSizeBytes: Long = DEFAULT_MAX_SIZE_BYTES,
) {
    private val frames = LinkedHashMap<KtxAnimationSource, ImageBitmap>(8, 0.75f, true)
    private var sizeBytes = 0L

    public fun get(source: KtxAnimationSource): ImageBitmap? = frames[source]

    public fun put(source: KtxAnimationSource, frame: ImageBitmap?) {
        if (frame == null) {
            remove(source)
            return
        }
        val previous = frames.put(source, frame)
        sizeBytes += sizeOf(frame) - (previous?.let(::sizeOf) ?: 0L)
        trim()
    }

    public fun remove(source: KtxAnimationSource) {
        frames.remove(source)?.let { sizeBytes -= sizeOf(it) }
    }

    public fun clear() {
        frames.clear()
        sizeBytes = 0L
    }

    private fun trim() {
        if (sizeBytes <= maxSizeBytes) return
        val iterator = frames.entries.iterator()
        while (sizeBytes > maxSizeBytes && iterator.hasNext()) {
            val entry = iterator.next()
            sizeBytes -= sizeOf(entry.value)
            iterator.remove()
        }
    }

    private fun sizeOf(frame: ImageBitmap): Long =
        frame.width.toLong() * frame.height.toLong() * BYTES_PER_PIXEL

    private companion object {
        const val BYTES_PER_PIXEL = 4L
        const val DEFAULT_MAX_SIZE_BYTES = 4L * 1024 * 1024
    }
}

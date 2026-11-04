package com.zxhhyj.vap.decode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.os.Build
import com.zxhhyj.vap.log.VapLog
import com.zxhhyj.vap.mp4.Mp4BoxParser
import com.zxhhyj.vap.mp4.Mp4Source
import com.zxhhyj.vap.mp4.Mp4Sources
import com.zxhhyj.vap.mp4.VAP_POSTER_BOX_TYPE
import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapRect
import com.zxhhyj.vap.player.VapSource
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import java.lang.ref.WeakReference

/**
 * Shared frame-0 cache for Compose opening present and `vap-coil` posters.
 * Strong LRU is bounded by a byte budget; evicted entries stay in a weak map
 * so a still-referenced bitmap can be promoted back.
 *
 * Compose opening 与 `vap-coil` 海报共用的第 0 帧缓存。强引用 LRU 按字节预算淘汰，
 * 淘汰项进入弱引用表，仍被持有的位图可以再提升回来。
 */
public object VapPosterMemoryCache {
    private val lock = Any()
    private val maxBytes: Long =
        (Runtime.getRuntime().maxMemory() / 10L).coerceIn(6L * 1024L * 1024L, 32L * 1024L * 1024L)
    private var currentBytes = 0L

    private val strong = object : LinkedHashMap<VapPosterKey, VapPosterEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<VapPosterKey, VapPosterEntry>?): Boolean {
            return false
        }
    }
    private val weak = LinkedHashMap<VapPosterKey, WeakReference<VapPosterEntry>>()

    /**
     * Memory lookup only. Does not extract.
     *
     * 只查内存，不抽帧。
     */
    public fun get(key: VapPosterKey): VapPosterEntry? = synchronized(lock) {
        strong[key]?.takeIf { it.isUsable() }?.let { return it }
        val revived = weak.remove(key)?.get()?.takeIf { it.isUsable() } ?: return null
        putStrong(key, revived)
        revived
    }

    /** Lookup by source + crop extras. / 按源 + 裁切 extras 查找。 */
    public fun get(source: VapSource, config: VapConfig): VapPosterEntry? =
        get(VapPosterKey.of(source, config))

    /**
     * Returns a cached entry or extracts packed frame 0, composites RGB + alpha,
     * and keeps the packed pixels for the Vulkan opening texture.
     *
     * 命中缓存则返回；否则抽打包第 0 帧、合成 RGB+alpha，并保留打包像素供 Vulkan opening 纹理上传。
     */
    public fun load(source: VapSource, config: VapConfig): VapPosterEntry? {
        val key = VapPosterKey.of(source, config)
        get(key)?.let { cached ->
            if (cached.packedPixels != null) return cached
            val upgraded = cached.ensurePackedPixels(source, config)
            if (upgraded !== cached) put(key, upgraded)
            return upgraded
        }
        val entry = decodeVapPoster(source, config) ?: return null
        put(key, entry)
        return entry
    }

    /**
     * Store a Coil-decoded cropped poster so Compose opening can reuse it.
     *
     * 写入 Coil 抽出的裁切海报，供 Compose opening 复用。
     */
    public fun putCropped(source: VapSource, config: VapConfig, cropped: Bitmap): VapPosterEntry {
        val key = VapPosterKey.of(source, config)
        val existing = get(key)
        val entry = VapPosterEntry(
            cropped = cropped,
            packedPixels = existing?.packedPixels,
        )
        put(key, entry)
        return entry
    }

    /** Insert or replace. / 插入或替换。 */
    public fun put(key: VapPosterKey, entry: VapPosterEntry) {
        synchronized(lock) { putStrong(key, entry) }
    }

    /**
     * Drop a cached entry's packed pixels after they have been handed to a decoder
     * session — the session keeps its own reference for the Vulkan opening upload,
     * so the cache copy (≈1.8MB per entry) no longer needs to stay resident. The
     * Compose poster bitmap remains cached; a later [load] re-extracts the packed
     * frame on demand.
     *
     * 打包像素交付给解码会话后即可从缓存丢弃 —— 会话自己持有引用供 Vulkan opening
     * 上传，缓存副本（每个约 1.8MB）不必再常驻。Compose 海报位图仍保留在缓存里；
     * 后续 [load] 需要时会重新抽帧。
     */
    public fun releasePackedPixels(source: VapSource, config: VapConfig) {
        val key = VapPosterKey.of(source, config)
        synchronized(lock) {
            val entry = strong[key] ?: return
            val pixels = entry.packedPixels ?: return
            putStrong(key, entry.copy(packedPixels = null))
        }
    }

    private fun putStrong(key: VapPosterKey, entry: VapPosterEntry) {
        strong.remove(key)?.let { currentBytes -= it.byteSize }
        strong[key] = entry
        currentBytes += entry.byteSize
        weak.remove(key)
        trimLocked(keep = key)
    }

    private fun trimLocked(keep: VapPosterKey) {
        while (currentBytes > maxBytes && strong.size > 1) {
            val eldest = strong.entries.first()
            if (eldest.key == keep) break
            strong.remove(eldest.key)
            currentBytes -= eldest.value.byteSize
            weak[eldest.key] = WeakReference(eldest.value)
        }
        val weakIt = weak.entries.iterator()
        while (weakIt.hasNext()) {
            if (weakIt.next().value.get() == null) weakIt.remove()
        }
    }
}

/**
 * Coil-style memory key: source identity + crop extras.
 *
 * 对齐 Coil 的内存 key：源身份 + 裁切 extras。
 */
public data class VapPosterKey(
    val data: String,
    val extras: Map<String, String>,
) {
    public companion object {
        /** Key for [source] plus `rgbFrame` extras. / [source] 加上 `rgbFrame` extras 的键。 */
        public fun of(source: VapSource, config: VapConfig): VapPosterKey {
            val data = VapConfigMemoryCache.key(source)
            val rgb = config.rgbFrame
            return VapPosterKey(
                data = data,
                extras = mapOf(
                    "rgb" to "${rgb.x},${rgb.y},${rgb.w},${rgb.h}",
                    "video" to "${config.videoWidth}x${config.videoHeight}",
                ),
            )
        }
    }
}

/**
 * Cached packed-frame-0 assets. [cropped] is the composited still (RGB + alpha);
 * [packedPixels] is the packed canvas in retriever orientation, uploaded as a Vulkan RGBA texture.
 *
 * 缓存的打包第 0 帧。[cropped] 是 `rgbFrame` 静图；[packedPixels] 是与 retriever 第 0 帧
 * 同向的打包画布，用于上传 RGBA opening 纹理。
 */
public data class VapPosterEntry(
    val cropped: Bitmap,
    val packedPixels: IntArray?,
) {
    /** Estimated native bytes of [cropped] plus packed pixels. / [cropped] 与打包像素的估算原生字节。 */
    public val byteSize: Int
        get() {
            val bitmapBytes = cropped.allocationByteCount.coerceAtLeast(cropped.byteCount)
                .coerceAtLeast(cropped.width * cropped.height * 4)
            val pixelBytes = (packedPixels?.size ?: 0) * 4
            return bitmapBytes + pixelBytes
        }

    /** False when the bitmap was recycled. / 位图已被 recycle 时为 false。 */
    public fun isUsable(): Boolean = !cropped.isRecycled

    /**
     * Decode packed frame 0 on demand and attach [packedPixels]. No-op when already present.
     *
     * 按需解码打包第 0 帧并附上 [packedPixels]；已有则原样返回。
     */
    public fun ensurePackedPixels(source: VapSource, config: VapConfig): VapPosterEntry {
        if (packedPixels != null) return this
        val packed = extractPackedFrame(source, config) ?: return this
        val pixels = packedToVulkanPixels(packed)
        if (!packed.isRecycled) packed.recycle()
        return copy(packedPixels = pixels ?: packedPixels)
    }
}

/**
 * Decode packed frame 0 directly into the Vulkan opening-texture pixel array, without
 * compositing the cropped poster bitmap. Surface playback only needs the packed pixels;
 * skipping the crop saves a full 720x1280 ARGB bitmap per composition.
 *
 * 直接把打包第 0 帧解码成 Vulkan opening 纹理像素数组，不合成裁切海报位图。
 * Surface 播放只需要打包像素；跳过裁切可省下每 composition 一张 720x1280 ARGB 位图。
 */
public fun decodeVapPackedPixels(source: VapSource, config: VapConfig): IntArray? {
    val packed = extractPackedFrame(source, config) ?: return null
    val pixels = packedToVulkanPixels(packed)
    if (!packed.isRecycled) packed.recycle()
    return pixels
}

/**
 * Decode packed frame 0 and composite the cropped poster. Does not touch any cache.
 *
 * 解码打包第 0 帧并合成裁切海报。不写入任何缓存。
 */
public fun decodeVapPoster(source: VapSource, config: VapConfig): VapPosterEntry? {
    val packed = extractPackedFrame(source, config) ?: return null
    val cropped = compositeVapPoster(packed, config) ?: run {
        if (!packed.isRecycled) packed.recycle()
        return null
    }
    if (cropped !== packed && !packed.isRecycled) packed.recycle()
    return VapPosterEntry(cropped = cropped, packedPixels = null)
}

internal fun extractPackedFrame(source: VapSource, config: VapConfig): Bitmap? {
    extractPackedFrameFromVapp(source, config)?.let { return it }
    val retriever = MediaMetadataRetriever()
    var fileRegion: VapAndroidSourceRegion? = null
    return try {
        when (source) {
            is VapSource.Bytes -> retriever.setDataSource(VapByteArrayMediaDataSource(source.data))
            is VapSource.AbsolutePath, is VapSource.Asset, is VapSource.Region -> {
                val opened = openVapAndroidSourceRegion(source)
                fileRegion = opened
                retriever.setDataSource(opened.descriptor, opened.offset, opened.length)
            }
        }
        val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: return null
        val w = config.videoWidth.coerceAtLeast(1)
        val h = config.videoHeight.coerceAtLeast(1)
        if (frame.width == w && frame.height == h) return frame
        val packed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(packed).drawBitmap(frame, 0f, 0f, null)
        frame.recycle()
        packed
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // Corrupt source / OOM on the packed bitmap — poster silently missing otherwise.
        // 源损坏 / 打包位图 OOM —— 否则海报会静默缺失。
        VapLog.w("VapPoster", t) { "extractPackedFrame failed for $source" }
        null
    } finally {
        try {
            retriever.release()
        } finally {
            fileRegion?.close()
        }
    }
}

/**
 * Read the embedded `vapp` poster (packed frame-0 PNG written by the vap tool)
 * straight from the mp4 tail. Returns null when the box is absent — the caller
 * then falls back to the MediaMetadataRetriever path. Decoding a pre-baked PNG
 * costs ~5ms vs ~50ms for a codec-backed frame grab, and skips the retriever's
 * extractor/codec setup entirely.
 *
 * 直接读取 mp4 尾部内嵌的 `vapp` 海报（vap 工具写入的打包第 0 帧 PNG）。box
 * 不存在时返回 null —— 调用方回退到 MediaMetadataRetriever 路径。解码预烘焙的
 * PNG 约 5ms，对比 codec 抽帧约 50ms，且完全省掉 retriever 的 extractor/codec
 * 初始化。
 */
private fun extractPackedFrameFromVapp(source: VapSource, config: VapConfig): Bitmap? {
    val mp4 = try {
        openMp4Source(source)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        VapLog.w("VapPoster", e) { "open vapp source failed for $source" }
        return null
    }
    return try {
        val payload = Mp4BoxParser.findPayload(mp4, VAP_POSTER_BOX_TYPE) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(payload, 0, payload.size) ?: return null
        val w = config.videoWidth.coerceAtLeast(1)
        val h = config.videoHeight.coerceAtLeast(1)
        if (bitmap.width != w || bitmap.height != h) {
            // The poster must match the packed-frame layout exactly or every
            // region computation downstream would be off; discard and fall back.
            // 海报必须与打包帧布局完全一致，否则下游所有区域换算都会错位；丢弃回退。
            bitmap.recycle()
            VapLog.w("VapPoster") { "vapp ${bitmap.width}x${bitmap.height} != video ${w}x$h, fallback to retriever" }
            return null
        }
        bitmap
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        VapLog.w("VapPoster", t) { "read vapp box failed for $source" }
        null
    } finally {
        (mp4 as? Closeable)?.close()
    }
}

private fun openMp4Source(source: VapSource): Mp4Source = when (source) {
    is VapSource.Bytes -> Mp4Sources.bytes(source.data)
    is VapSource.AbsolutePath, is VapSource.Asset, is VapSource.Region ->
        openVapAndroidSourceRegion(source).use { it.openMp4Source() }
}

/**
 * Convert the packed frame to ARGB pixels in retriever orientation. The opening texture is a
 * normally uploaded RGBA image; the shared native UVs already align it with the codec frame, so a
 * CPU-side vertical flip makes the opening frame upside down.
 *
 * 把打包帧转成 retriever 方向的 ARGB 像素。opening 纹理是正常上传的 RGBA 图；共享原生 UV
 * 已会把它对齐到 codec 帧，因此不能再做 CPU 垂直翻转，否则首帧会颠倒。
 */
internal fun packedToVulkanPixels(packed: Bitmap): IntArray? {
    val w = packed.width.coerceAtLeast(1)
    val h = packed.height.coerceAtLeast(1)
    val pixels = IntArray(w * h)
    return try {
        packed.getPixels(pixels, 0, w, 0, 0, w, h)
        pixels
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        VapLog.w("VapPoster", t) { "getPixels failed ${packed.width}x${packed.height} recycled=${packed.isRecycled}" }
        null
    }
}

/**
 * Crops [VapConfig.rgbFrame] and, when [VapConfig.hasAlpha], writes opacity from the
 * alpha region's red channel — same rule as the Vulkan fragment shader.
 *
 * 裁 [VapConfig.rgbFrame]；[VapConfig.hasAlpha] 时用 alpha 区红通道写不透明度，
 * 与 Vulkan 片元着色器同一条规则。
 */
public fun compositeVapPoster(packed: Bitmap, config: VapConfig): Bitmap? {
    val src = packed.toSoftwareArgb() ?: return null
    val vw = config.videoWidth.coerceAtLeast(1)
    val vh = config.videoHeight.coerceAtLeast(1)
    val sx = src.width.toFloat() / vw
    val sy = src.height.toFloat() / vh
    val rgb = scaledRect(config.rgbFrame, sx, sy, src.width, src.height) ?: return null
    if (!config.hasAlpha || config.alphaFrame.w <= 0 || config.alphaFrame.h <= 0) {
        return Bitmap.createBitmap(src, rgb.x, rgb.y, rgb.w, rgb.h)
    }
    val alpha = scaledRect(config.alphaFrame, sx, sy, src.width, src.height) ?: return null
    val rgbPx = IntArray(rgb.w * rgb.h)
    val aPx = IntArray(alpha.w * alpha.h)
    src.getPixels(rgbPx, 0, rgb.w, rgb.x, rgb.y, rgb.w, rgb.h)
    src.getPixels(aPx, 0, alpha.w, alpha.x, alpha.y, alpha.w, alpha.h)
    for (i in rgbPx.indices) {
        val px = i % rgb.w
        val py = i / rgb.w
        val ax = px * alpha.w / rgb.w
        val ay = py * alpha.h / rgb.h
        val a = (aPx[ay * alpha.w + ax] ushr 16) and 0xff
        rgbPx[i] = (a shl 24) or (rgbPx[i] and 0x00FFFFFF)
    }
    val out = Bitmap.createBitmap(rgb.w, rgb.h, Bitmap.Config.ARGB_8888)
    out.setHasAlpha(true)
    out.setPixels(rgbPx, 0, rgb.w, 0, 0, rgb.w, rgb.h)
    return out
}

private fun scaledRect(rect: VapRect, sx: Float, sy: Float, maxW: Int, maxH: Int): VapRect? {
    val x = (rect.x * sx).toInt().coerceIn(0, (maxW - 1).coerceAtLeast(0))
    val y = (rect.y * sy).toInt().coerceIn(0, (maxH - 1).coerceAtLeast(0))
    val w = (rect.w * sx).toInt().coerceAtMost(maxW - x).coerceAtLeast(1)
    val h = (rect.h * sy).toInt().coerceAtMost(maxH - y).coerceAtLeast(1)
    if (w <= 0 || h <= 0) return null
    return VapRect(x, y, w, h)
}

private fun Bitmap.toSoftwareArgb(): Bitmap? {
    if (Build.VERSION.SDK_INT >= 26 && config == Bitmap.Config.HARDWARE) {
        return copy(Bitmap.Config.ARGB_8888, false)
    }
    if (config != Bitmap.Config.ARGB_8888) {
        return copy(Bitmap.Config.ARGB_8888, false)
    }
    return this
}

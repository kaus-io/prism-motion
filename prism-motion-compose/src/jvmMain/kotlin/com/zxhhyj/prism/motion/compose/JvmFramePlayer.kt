package com.zxhhyj.prism.motion.compose

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.zxhhyj.prism.motion.PrismMotionResource
import com.zxhhyj.prism.motion.PrismMotionSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.takeWhile
import java.awt.image.BufferedImage

/** 单调显示时钟时间轴；迟到时跳帧，不累计补播工作。 / Monotonic display timeline; skips missed frames. */
internal class JvmFrameTimeline(private val frameCount: Int, private val fps: Int) {
    init {
        require(frameCount > 0 && fps > 0)
    }

    // 容忍刷新时间戳的微小舍入误差，避免额外等待一次刷新。 / Tolerates refresh timestamp rounding without an extra tick.
    private val initialFraction = minOf(1_000_000L, 250_000_000L / fps) * fps
    private var fraction = initialFraction
    private var absoluteFrame = 0L
    private var previousTime: Long? = null
    private var repeats = 0
    var isComplete: Boolean = false
        private set
    val frameIndex: Int get() = (absoluteFrame % frameCount).toInt()

    fun setRepeatCount(repeatCount: Int) {
        require(repeatCount >= -1) { "repeatCount must be -1 or nonnegative" }
        repeats = repeatCount
    }

    fun tick(frameTimeNanos: Long) {
        if (isComplete) return
        val previous = previousTime
        previousTime = if (previous == null) frameTimeNanos else maxOf(previous, frameTimeNanos)
        if (previous == null || frameTimeNanos <= previous) return
        val delta = (frameTimeNanos - previous).let { if (it < 0) Long.MAX_VALUE else it }
        val scaledFraction = fraction + (delta % NANOS_PER_SECOND) * fps
        fraction = scaledFraction % NANOS_PER_SECOND
        val wholeFrames = saturatingMultiply(delta / NANOS_PER_SECOND, fps.toLong())
        advanceBy(saturatingAdd(wholeFrames, scaledFraction / NANOS_PER_SECOND))
    }

    fun pause() {
        previousTime = null
    }

    fun seekTo(index: Int) {
        absoluteFrame = index.coerceIn(0, frameCount - 1).toLong()
        fraction = initialFraction
        previousTime = null
        isComplete = false
    }

    fun step() {
        previousTime = null
        advanceBy(1L)
    }

    private fun advanceBy(frames: Long) {
        absoluteFrame = saturatingAdd(absoluteFrame, frames)
        if (repeats >= 0) {
            val limit = (repeats + 1L) * frameCount
            if (absoluteFrame >= limit) {
                absoluteFrame = limit - 1
                isComplete = true
            }
        }
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private fun saturatingMultiply(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

/** 字节数和条目数均受限的 LRU；超预算帧可显示但不保留。 / Byte/entry-bounded LRU; oversized frames are uncached. */
internal class JvmFrameCache<T>(
    private val maxBytes: Long,
    private val maxEntries: Int,
    private val sizeOf: (T) -> Long,
) {
    init {
        require(maxBytes >= 0 && maxEntries >= 0)
    }

    private data class Entry<T>(val value: T, val bytes: Long)
    private val entries = LinkedHashMap<Int, Entry<T>>(4, 0.75f, true)
    var sizeBytes: Long = 0L
        private set
    val entryCount: Int get() = entries.size

    fun getOrPut(index: Int, load: () -> T): T {
        entries[index]?.let { return it.value }
        val value = load()
        val bytes = sizeOf(value)
        require(bytes >= 0)
        if (maxEntries == 0 || bytes > maxBytes) return value
        while (entries.isNotEmpty() && (entries.size >= maxEntries || sizeBytes > maxBytes - bytes)) {
            val oldest = entries.entries.iterator()
            sizeBytes -= oldest.next().value.bytes
            oldest.remove()
        }
        entries[index] = Entry(value, bytes)
        sizeBytes += bytes
        return value
    }

    fun clear() {
        entries.clear()
        sizeBytes = 0L
    }
}

/**
 * JVM 已解码帧资源：显示时钟推进、有限重复与有界位图缓存；只在实际绘制后确认输出。
 * Shared JVM frames: display-clock pacing, finite repeats, bounded bitmap cache, and draw acknowledgement.
 */
public abstract class JvmFrameResource(
    public val frameWidth: Int,
    public val frameHeight: Int,
    public val frameCount: Int,
    public val fps: Int,
    bitmapCacheBytes: Long = 8L * 1024 * 1024,
    bitmapCacheEntries: Int = 4,
) : PrismMotionResource {
    init {
        require(frameWidth > 0 && frameHeight > 0)
    }

    public var frameIndex: Int by mutableStateOf(0)
        private set

    private enum class Playback { Paused, Playing, Released }
    private val playback = MutableStateFlow(Playback.Paused)
    private val timeline = JvmFrameTimeline(frameCount, fps)
    private val bitmaps = JvmFrameCache<ImageBitmap>(bitmapCacheBytes, bitmapCacheEntries) {
        it.width.toLong() * it.height * 4L
    }
    private var playingState by mutableStateOf(false)
    private var completedState by mutableStateOf(false)
    internal var isReleased: Boolean by mutableStateOf(false)
        private set
    internal var frameVersion: Long by mutableLongStateOf(0L)
        private set
    private var lastDrawnVersion = -1L
    private var outputState by mutableStateOf(false)
    private var drawnFrames by mutableLongStateOf(0L)
    private var failure by mutableStateOf<Throwable?>(null)

    /** 第 [index] 帧的位图；子类实现。 / Bitmap of frame [index]; implemented by subclasses. */
    protected abstract fun renderFrame(index: Int): ImageBitmap

    /** 释放子类拥有的解码器或数据；只执行一次。 / Releases subclass-owned decoders/data exactly once. */
    protected open fun onRelease() = Unit

    /** 获取像素不代表已经显示；仅缓存预算内的帧。 / Fetching pixels is not presentation; caches only within budget. */
    public fun currentBitmap(): ImageBitmap {
        check(!isReleased) { "resource is released" }
        failure?.let { throw IllegalStateException("frame rendering failed", it) }
        return try {
            bitmaps.getOrPut(frameIndex) { renderFrame(frameIndex) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
            outputState = false
            pause()
            throw e
        }
    }

    /** 手动推进一帧；显示宿主使用单调时钟而非此入口。 / Manual step; display hosts use the monotonic clock instead. */
    public fun advance() {
        if (!isPlaying) return
        timeline.step()
        publishTimeline()
    }

    override val width: Int get() = frameWidth
    override val height: Int get() = frameHeight
    override val durationMs: Long get() = frameCount * 1000L / fps
    override val hasOutput: Boolean get() = outputState && !isReleased && failure == null
    override val progress: Float get() = if (completedState) 1f else frameIndex.toFloat() / frameCount
    override val isPlaying: Boolean get() = playingState
    override val presentedFrameCount: Long get() = drawnFrames
    override val playbackError: Throwable? get() = failure

    override fun attach(surface: PrismMotionSurface, width: Int, height: Int) = Unit
    override fun resize(width: Int, height: Int) = Unit
    override fun detach() = onOutputDetached()

    override fun setRepeatCount(repeatCount: Int) {
        timeline.setRepeatCount(repeatCount)
    }

    override fun play() {
        if (isReleased || failure != null || playingState) return
        if (timeline.isComplete) {
            timeline.seekTo(0)
            publishTimeline()
        }
        timeline.pause()
        playingState = true
        playback.value = Playback.Playing
    }

    override fun pause() {
        timeline.pause()
        playingState = false
        if (!isReleased) playback.value = Playback.Paused
    }

    override fun seekTo(progress: Float) {
        if (isReleased) return
        require(progress.isFinite()) { "progress must be finite" }
        timeline.seekTo((progress.coerceIn(0f, 1f) * frameCount).toInt())
        frameIndex = timeline.frameIndex
        completedState = false
        frameVersion++
    }

    override fun release() {
        if (isReleased) return
        isReleased = true
        playingState = false
        playback.value = Playback.Released
        timeline.pause()
        onOutputDetached()
        bitmaps.clear()
        onRelease()
    }

    internal suspend fun runFrameClock() {
        try {
            playback.takeWhile { it != Playback.Released }
                .onCompletion { cause -> if (cause == null) emit(Playback.Released) }
                .collectLatest { command ->
                    if (command == Playback.Playing) {
                        while (isPlaying) withFrameNanos { onFrameTime(it) }
                    }
                }
        } finally {
            // 重挂宿主从当前帧继续，不计入离屏的墙钟时间。 / Reattachment resumes without counting detached wall time.
            timeline.pause()
        }
    }

    internal fun onFrameTime(frameTimeNanos: Long) {
        if (!isPlaying || isReleased) return
        timeline.tick(frameTimeNanos)
        publishTimeline()
    }

    internal fun onFrameDrawn(version: Long) {
        if (isReleased || failure != null || version != frameVersion || lastDrawnVersion == version) return
        lastDrawnVersion = version
        outputState = true
        drawnFrames++
    }

    internal fun onOutputDetached() {
        outputState = false
        lastDrawnVersion = -1L
    }

    private fun publishTimeline() {
        completedState = timeline.isComplete
        if (frameIndex != timeline.frameIndex) {
            frameIndex = timeline.frameIndex
            frameVersion++
        }
        if (timeline.isComplete) pause()
    }
}

/** 共享 Compose 显示时钟；暂停时不请求刷新，绘制后才统计帧。 / Shared Compose clock; no paused ticks, counts only draws. */
public abstract class JvmFrameRenderer : PrismMotionRenderer {
    @Composable
    override fun Render(state: PrismMotionState, modifier: Modifier, contentScale: ContentScale) {
        val resource = state.resource as? JvmFrameResource ?: return
        DisposableEffect(resource) {
            onDispose { resource.onOutputDetached() }
        }
        LaunchedEffect(resource) { resource.runFrameClock() }
        if (resource.isReleased || resource.playbackError != null) return
        val version = resource.frameVersion
        val bitmap = remember(resource, version) {
            try {
                resource.currentBitmap()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } ?: return
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.drawWithContent {
                drawContent()
                resource.onFrameDrawn(version)
            },
            contentScale = contentScale,
        )
    }
}

/** RGBA 像素转 Compose 位图。 / Converts RGBA pixels into a Compose bitmap. */
public fun rgbaToImageBitmap(width: Int, height: Int, rgba: ByteArray): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val pixels = IntArray(width * height)
    for (index in pixels.indices) {
        val offset = index * 4
        val r = rgba[offset].toInt() and 0xFF
        val g = rgba[offset + 1].toInt() and 0xFF
        val b = rgba[offset + 2].toInt() and 0xFF
        val a = rgba[offset + 3].toInt() and 0xFF
        pixels[index] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}

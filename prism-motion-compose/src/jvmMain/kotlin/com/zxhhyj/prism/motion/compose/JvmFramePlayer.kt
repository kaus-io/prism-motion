package com.zxhhyj.prism.motion.compose

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.zxhhyj.prism.motion.AnimationResource
import com.zxhhyj.prism.motion.AnimationSurface
import kotlinx.coroutines.delay
import java.awt.image.BufferedImage

/**
 * JVM 侧"已解码帧序列"的公共资源：负责帧索引、播放状态、位图缓存。
 * 子类只需提供每帧的位图。 / Shared JVM resource for decoded frame sequences.
 */
public abstract class JvmFrameResource(
    public val frameWidth: Int,
    public val frameHeight: Int,
    public val frameCount: Int,
    public val fps: Int,
) : AnimationResource {

    public var frameIndex: Int by mutableStateOf(0)
        private set

    private var playingState by mutableStateOf(false)

    private var cachedIndex = -1
    private var cachedBitmap: ImageBitmap? = null

    /** 第 [index] 帧的位图；子类实现。 / Bitmap of frame [index]; implemented by subclasses. */
    protected abstract fun renderFrame(index: Int): ImageBitmap

    public fun currentBitmap(): ImageBitmap {
        if (cachedIndex == frameIndex && cachedBitmap != null) return cachedBitmap!!
        val bitmap = renderFrame(frameIndex)
        cachedIndex = frameIndex
        cachedBitmap = bitmap
        return bitmap
    }

    public fun advance() {
        if (playingState) frameIndex = (frameIndex + 1) % frameCount
    }

    override val width: Int get() = frameWidth
    override val height: Int get() = frameHeight
    override val durationMs: Long get() = frameCount * 1000L / fps.coerceAtLeast(1)
    override val hasOutput: Boolean get() = true
    override val progress: Float get() = frameIndex.toFloat() / frameCount
    override val isPlaying: Boolean get() = playingState

    override fun attach(surface: AnimationSurface, width: Int, height: Int) = Unit
    override fun resize(width: Int, height: Int) = Unit
    override fun detach() = Unit

    override fun play() {
        playingState = true
    }

    override fun pause() {
        playingState = false
    }

    override fun seekTo(progress: Float) {
        frameIndex = (progress.coerceIn(0f, 1f) * frameCount).toInt().coerceIn(0, frameCount - 1)
    }

    override fun release() {
        playingState = false
        cachedBitmap = null
        cachedIndex = -1
    }
}

/**
 * JVM 渲染器基类：把当前帧画出来，并用协程按 fps 推进。
 * JVM renderer base: draws the current frame and advances it at `fps`.
 */
public abstract class JvmFrameRenderer : AnimationRenderer {

    @Composable
    override fun Render(state: AnimationState, modifier: Modifier, contentScale: ContentScale) {
        val resource = state.resource as? JvmFrameResource ?: return
        val bitmap = remember(resource, resource.frameIndex) { resource.currentBitmap() }
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier, contentScale = contentScale)

        val frameDelay = remember(resource) { 1000L / resource.fps.coerceAtLeast(1) }
        val playing by rememberUpdatedState(resource.isPlaying)
        LaunchedEffect(resource) {
            while (true) {
                delay(frameDelay)
                if (playing) resource.advance()
            }
        }
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

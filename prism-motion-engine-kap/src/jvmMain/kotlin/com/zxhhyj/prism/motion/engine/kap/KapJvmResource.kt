package com.zxhhyj.prism.motion.engine.kap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.zxhhyj.prism.motion.AnimationResource
import com.zxhhyj.prism.motion.AnimationSurface
import java.awt.image.BufferedImage

/**
 * JVM 侧的 KAP 资源：持有解码后的帧，按 fps 推进；渲染器只负责把当前帧画出来。
 * JVM KAP resource: holds decoded frames, advances at `fps`; the renderer just draws the current one.
 */
internal class KapJvmResource(private val animation: Ktx2Animation) : AnimationResource {

    var frameIndex by mutableStateOf(0)
        private set

    var playing by mutableStateOf(false)
        private set

    override val width: Int get() = animation.width
    override val height: Int get() = animation.height
    override val durationMs: Long get() = animation.frames.size * 1000L / animation.fps
    override val hasOutput: Boolean get() = true
    override val progress: Float get() = frameIndex.toFloat() / animation.frames.size
    override val isPlaying: Boolean get() = playing

    val fps: Int get() = animation.fps
    val frameCount: Int get() = animation.frames.size

    private var cachedIndex = -1
    private var cachedBitmap: ImageBitmap? = null

    fun currentBitmap(): ImageBitmap {
        if (cachedIndex == frameIndex && cachedBitmap != null) return cachedBitmap!!
        val frame = animation.frames[frameIndex]
        val bitmap = frame.toImageBitmap()
        cachedIndex = frameIndex
        cachedBitmap = bitmap
        return bitmap
    }

    fun advance(): Boolean {
        if (!playing) return false
        frameIndex = (frameIndex + 1) % animation.frames.size
        return true
    }

    override fun attach(surface: AnimationSurface, width: Int, height: Int) = Unit
    override fun resize(width: Int, height: Int) = Unit
    override fun detach() = Unit

    override fun play() {
        playing = true
    }

    override fun pause() {
        playing = false
    }

    override fun seekTo(progress: Float) {
        val index = (progress.coerceIn(0f, 1f) * animation.frames.size).toInt()
        frameIndex = index.coerceIn(0, animation.frames.size - 1)
    }

    override fun release() {
        playing = false
        cachedBitmap = null
        cachedIndex = -1
    }

    private fun Ktx2Frame.toImageBitmap(): ImageBitmap {
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
}

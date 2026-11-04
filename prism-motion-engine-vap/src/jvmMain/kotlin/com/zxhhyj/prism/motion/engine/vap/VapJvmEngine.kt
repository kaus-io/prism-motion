package com.zxhhyj.prism.motion.engine.vap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.zxhhyj.prism.motion.AnimationEngine
import com.zxhhyj.prism.motion.AnimationEngineId
import com.zxhhyj.prism.motion.AnimationResource
import com.zxhhyj.prism.motion.AnimationSource
import com.zxhhyj.prism.motion.AnimationSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File

/**
 * VAP 引擎的 JVM 实现：自有 MP4 解封装 + OpenH264 解码（JNA），不再依赖 FFmpeg。
 * JVM VAP engine: own MP4 demuxer + OpenH264 decoding via JNA; no FFmpeg.
 */
public class VapAnimationEngine : AnimationEngine {

    override val id: AnimationEngineId = AnimationEngineId.Vap

    override fun supports(source: AnimationSource): Boolean = when (source) {
        is AnimationSource.Asset -> source.path.endsWith(SUFFIX, ignoreCase = true)
        is AnimationSource.File -> source.path.endsWith(SUFFIX, ignoreCase = true)
        is AnimationSource.Bytes -> true
    }

    override suspend fun open(source: AnimationSource): AnimationResource {
        val bytes = when (source) {
            is AnimationSource.Asset -> error("VAP JVM 不支持 Android asset: ${source.path}")
            is AnimationSource.File -> File(source.path).readBytes()
            is AnimationSource.Bytes -> source.bytes
        }
        val (frames, config) = withContext(Dispatchers.IO) {
            val demuxed = VapMp4.demux(bytes)
            val i420Frames = VapDecodeNative.decodeAll(
                demuxed.samples,
                demuxed.config.videoWidth,
                demuxed.config.videoHeight,
            )
            i420Frames.map { VapFrames.compose(it, demuxed.config) } to demuxed.config
        }
        check(frames.isNotEmpty()) { "VAP 解码没有产出帧" }
        return VapJvmResource(frames, config.fps)
    }

    private companion object {
        const val SUFFIX = ".mp4"
    }
}

/** JVM 侧的 VAP 资源：帧已解码完成，按 fps 推进。 / JVM VAP resource: decoded frames advanced at `fps`. */
internal class VapJvmResource(
    private val frames: List<VapFrame>,
    val fps: Int,
) : AnimationResource {

    var frameIndex by mutableStateOf(0)
        private set
    var playing by mutableStateOf(false)
        private set

    private var cachedIndex = -1
    private var cachedBitmap: ImageBitmap? = null

    override val width: Int get() = frames.first().width
    override val height: Int get() = frames.first().height
    override val durationMs: Long get() = frames.size * 1000L / fps.coerceAtLeast(1)
    override val hasOutput: Boolean get() = true
    override val progress: Float get() = frameIndex.toFloat() / frames.size
    override val isPlaying: Boolean get() = playing

    fun currentBitmap(): ImageBitmap {
        if (cachedIndex == frameIndex && cachedBitmap != null) return cachedBitmap!!
        val bitmap = frames[frameIndex].toImageBitmap()
        cachedIndex = frameIndex
        cachedBitmap = bitmap
        return bitmap
    }

    fun advance() {
        if (playing) frameIndex = (frameIndex + 1) % frames.size
    }

    override fun attach(surface: AnimationSurface, width: Int, height: Int) = Unit
    override fun resize(width: Int, height: Int) = Unit
    override fun detach() = Unit
    override fun play() { playing = true }
    override fun pause() { playing = false }

    override fun seekTo(progress: Float) {
        frameIndex = (progress.coerceIn(0f, 1f) * frames.size).toInt().coerceIn(0, frames.size - 1)
    }

    override fun release() {
        playing = false
        cachedBitmap = null
        cachedIndex = -1
    }

    private fun VapFrame.toImageBitmap(): ImageBitmap {
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

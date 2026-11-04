package com.zxhhyj.prism.motion.engine.vap

import androidx.compose.ui.graphics.ImageBitmap
import com.zxhhyj.prism.motion.AnimationEngine
import com.zxhhyj.prism.motion.AnimationEngineId
import com.zxhhyj.prism.motion.AnimationResource
import com.zxhhyj.prism.motion.AnimationSource
import com.zxhhyj.prism.motion.compose.JvmFrameResource
import com.zxhhyj.prism.motion.compose.rgbaToImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** JVM 侧的 VAP 资源：帧已在打开时解码，这里只负责按索引取位图。 / JVM VAP resource: frames are pre-decoded. */
internal class VapJvmResource(
    private val frames: List<VapFrame>,
    fps: Int,
) : JvmFrameResource(
    frameWidth = frames.first().width,
    frameHeight = frames.first().height,
    frameCount = frames.size,
    fps = fps,
) {
    override fun renderFrame(index: Int): ImageBitmap {
        val frame = frames[index]
        return rgbaToImageBitmap(frame.width, frame.height, frame.rgba)
    }
}

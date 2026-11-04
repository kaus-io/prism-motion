package com.zxhhyj.prism.motion.engine.vap

import androidx.compose.ui.graphics.ImageBitmap
import com.zxhhyj.prism.motion.PrismMotionEngine
import com.zxhhyj.prism.motion.PrismMotionEngineId
import com.zxhhyj.prism.motion.PrismMotionResource
import com.zxhhyj.prism.motion.PrismMotionSource
import com.zxhhyj.prism.motion.PrismJvmSources
import com.zxhhyj.prism.motion.integerPlaybackFps
import com.zxhhyj.prism.motion.requireNativeMetadata
import com.zxhhyj.prism.motion.format.PrismPayloadKind
import com.zxhhyj.prism.motion.format.PrismAlphaMode
import com.zxhhyj.prism.motion.compose.JvmFrameResource
import com.zxhhyj.prism.motion.compose.rgbaToImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * VAP 引擎的 JVM 实现：自有 MP4 解封装 + OpenH264 解码（JNA），不再依赖 FFmpeg。
 * JVM VAP engine: own MP4 demuxer + OpenH264 decoding via JNA; no FFmpeg.
 */
public class PrismMotionVapEngine : PrismMotionEngine {

    override val id: PrismMotionEngineId = PrismMotionEngineId.Vap

    override suspend fun supports(source: PrismMotionSource): Boolean = withContext(Dispatchers.IO) {
        PrismJvmSources.supports(source, PrismPayloadKind.Vap)
    }

    override suspend fun open(source: PrismMotionSource): PrismMotionResource {
        val (frames, config) = withContext(Dispatchers.IO) {
            val payload = PrismJvmSources.payload(source)
            require(payload.descriptor.kind == PrismPayloadKind.Vap) { "VAP requires a Prism video payload" }
            val metadata = payload.descriptor.metadata
            metadata.integerPlaybackFps()
            val demuxed = VapMp4.demux(payload.bytes)
            val native = demuxed.config
            metadata.requireNativeMetadata(native.width, native.height, native.frameCount, native.fps)
            require(native.hasAlpha == (metadata.alphaMode != PrismAlphaMode.Opaque)) { "Prism alpha mode does not match video payload" }
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

}

/** JVM 侧的 VAP 资源：帧已在打开时解码，这里只负责按索引取位图。 / JVM VAP resource: frames are pre-decoded. */
internal class VapJvmResource(
    frames: List<VapFrame>,
    fps: Int,
) : JvmFrameResource(
    frameWidth = frames.first().width,
    frameHeight = frames.first().height,
    frameCount = frames.size,
    fps = fps,
) {
    private var frames = frames

    override fun onRelease() {
        frames = emptyList()
    }

    override fun renderFrame(index: Int): ImageBitmap {
        val frame = frames[index]
        return rgbaToImageBitmap(frame.width, frame.height, frame.rgba)
    }
}

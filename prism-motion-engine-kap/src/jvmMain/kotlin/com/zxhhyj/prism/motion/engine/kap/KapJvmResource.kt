package com.zxhhyj.prism.motion.engine.kap

import androidx.compose.ui.graphics.ImageBitmap
import com.zxhhyj.prism.motion.compose.JvmFrameResource
import com.zxhhyj.prism.motion.compose.rgbaToImageBitmap

/**
 * JVM 侧的 KAP 资源：帧已在打开时解码，这里只负责按索引取位图。
 * JVM KAP resource: frames are decoded on open; this only serves bitmaps by index.
 */
internal class KapJvmResource(private val animation: KtxAnimation) : JvmFrameResource(
    frameWidth = animation.width,
    frameHeight = animation.height,
    frameCount = animation.frames.size,
    fps = animation.fps,
) {
    override fun renderFrame(index: Int): ImageBitmap {
        val frame = animation.frames[index]
        return rgbaToImageBitmap(frame.width, frame.height, frame.rgba)
    }
}

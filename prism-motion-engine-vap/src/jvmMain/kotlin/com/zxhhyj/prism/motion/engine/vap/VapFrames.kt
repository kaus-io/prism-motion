package com.zxhhyj.prism.motion.engine.vap

/** 一帧 RGBA（已按 vapc 布局把蒙版合成为 alpha）。 / RGBA frame with the vapc mask folded into alpha. */
internal class VapFrame(val width: Int, val height: Int, val rgba: ByteArray)

/** I420 → RGBA，并按 vapc 的 rgb/aFrame 合成为最终画面。 / I420 to RGBA with vapc layout compositing. */
internal object VapFrames {

    fun compose(i420: ByteArray, config: VapConfig): VapFrame {
        val videoWidth = config.videoWidth
        val videoHeight = config.videoHeight
        val outWidth = config.width
        val outHeight = config.height
        val out = ByteArray(outWidth * outHeight * 4)

        val ySize = videoWidth * videoHeight
        val uvSize = (videoWidth / 2) * (videoHeight / 2)
        val yPlane = 0
        val uPlane = ySize
        val vPlane = ySize + uvSize

        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                val sx = config.rgbX + x
                val sy = config.rgbY + y
                if (sx < 0 || sy < 0 || sx >= videoWidth || sy >= videoHeight) continue
                val luma = i420[yPlane + sy * videoWidth + sx].toInt() and 0xFF
                val chromaX = sx / 2
                val chromaY = sy / 2
                val u = i420[uPlane + chromaY * (videoWidth / 2) + chromaX].toInt() and 0xFF
                val v = i420[vPlane + chromaY * (videoWidth / 2) + chromaX].toInt() and 0xFF

                val c = (luma - 16).coerceAtLeast(0)
                val d = u - 128
                val e = v - 128
                val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
                val alpha = if (config.hasAlpha) sampleAlpha(i420, config, x, y) else 255

                val offset = (y * outWidth + x) * 4
                out[offset] = r.toByte()
                out[offset + 1] = g.toByte()
                out[offset + 2] = b.toByte()
                out[offset + 3] = alpha.toByte()
            }
        }
        return VapFrame(outWidth, outHeight, out)
    }

    /** 蒙版是缩小后的灰度区域，这里最近邻放大回内容尺寸。 / Nearest-neighbour upscale of the mask region. */
    private fun sampleAlpha(i420: ByteArray, config: VapConfig, x: Int, y: Int): Int {
        if (config.alphaWidth <= 0 || config.alphaHeight <= 0) return 255
        val mx = config.alphaX + (x * config.alphaWidth / config.width)
        val my = config.alphaY + (y * config.alphaHeight / config.height)
        if (mx < 0 || my < 0 || mx >= config.videoWidth || my >= config.videoHeight) return 255
        return i420[my * config.videoWidth + mx].toInt() and 0xFF
    }
}

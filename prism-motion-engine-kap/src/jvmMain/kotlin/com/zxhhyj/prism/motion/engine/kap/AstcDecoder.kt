package com.zxhhyj.prism.motion.engine.kap

import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * ASTC 解码：把 KTX2 里的裸块包成 `.astc` 文件，交给外部 astcenc 解成 PNG 再读回 RGBA。
 * ASTC decoding: wrap raw KTX2 blocks into an `.astc` file, let astcenc decode to PNG, read back as RGBA.
 *
 * astcenc 不是 JVM 库，所以这里依赖可执行文件：`PRISM_MOTION_ASTCENC` 环境变量、
 * `~/.cache/prism-motion/tools/astcenc` 或 PATH 中的 `astcenc`。
 */
internal object AstcDecoder {

    fun decode(blocks: ByteArray, width: Int, height: Int, vkFormat: Int): ByteArray {
        val (blockWidth, blockHeight) = blockSize(vkFormat)
        val tool = findTool()
        val dir = Files.createTempDirectory("prism-kap-astc").toFile()
        try {
            val astc = File(dir, "layer.astc")
            astc.writeBytes(astcFile(blocks, width, height, blockWidth, blockHeight))
            val png = File(dir, "layer.png")
            val process = ProcessBuilder(tool, "-dl", astc.absolutePath, png.absolutePath)
                .redirectErrorStream(true)
                .start()
            process.inputStream.readBytes()
            val exit = process.waitFor()
            check(exit == 0) { "astcenc 解码失败（退出码 $exit）" }
            val image = ImageIO.read(png) ?: error("无法读取 astcenc 输出")
            return toRgba(image)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun blockSize(vkFormat: Int): Pair<Int, Int> = when (vkFormat) {
        157 -> 4 to 4
        165 -> 6 to 6
        171 -> 8 to 8
        179 -> 10 to 10
        183 -> 12 to 12
        else -> error("不是 ASTC 格式: $vkFormat")
    }

    /** ASTC 文件头：magic + 块尺寸 + (宽高各减一，24 位小端) 。 */
    private fun astcFile(blocks: ByteArray, width: Int, height: Int, blockWidth: Int, blockHeight: Int): ByteArray {
        val header = ByteArray(16)
        header[0] = 0x13
        header[1] = 0xAB.toByte()
        header[2] = 0xA1.toByte()
        header[3] = 0x5C
        header[4] = blockWidth.toByte()
        header[5] = blockHeight.toByte()
        header[6] = 1
        putU24(header, 7, width - 1)
        putU24(header, 10, height - 1)
        putU24(header, 13, 0)
        return header + blocks
    }

    private fun putU24(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    }

    private fun toRgba(image: java.awt.image.BufferedImage): ByteArray {
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        image.getRGB(0, 0, width, height, pixels, 0, width)
        val out = ByteArray(width * height * 4)
        for (index in pixels.indices) {
            val argb = pixels[index]
            val offset = index * 4
            out[offset] = ((argb ushr 16) and 0xFF).toByte()
            out[offset + 1] = ((argb ushr 8) and 0xFF).toByte()
            out[offset + 2] = (argb and 0xFF).toByte()
            out[offset + 3] = ((argb ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private fun findTool(): String {
        System.getenv("PRISM_MOTION_ASTCENC")?.takeIf { it.isNotBlank() }?.let { return it }
        val cached = File(System.getProperty("user.home"), ".cache/prism-motion/tools/astcenc")
        if (cached.canExecute()) return cached.absolutePath
        return "astcenc"
    }
}

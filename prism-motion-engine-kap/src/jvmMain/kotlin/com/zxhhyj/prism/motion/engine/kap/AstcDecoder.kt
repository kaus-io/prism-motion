package com.zxhhyj.prism.motion.engine.kap

import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.imageio.ImageIO

/** 原生库优先，缺库时才退回 astcenc 工具。 / Uses the native library, falling back to the CLI only when unavailable. */
internal object AstcDecoder {
    private val footprints = listOf(
        4 to 4, 5 to 4, 5 to 5, 6 to 5, 6 to 6, 8 to 5, 8 to 6,
        8 to 8, 10 to 5, 10 to 6, 10 to 8, 10 to 10, 12 to 10, 12 to 12,
    )

    fun decode(blocks: ByteArray, width: Int, height: Int, vkFormat: Int): ByteArray {
        val layout = layout(width, height, vkFormat)
        layout.validate(blocks)
        AstcNative.decode(blocks, width, height, layout.blockWidth, layout.blockHeight, layout.srgb)?.let { return it }
        return decodeWithTool(blocks, layout, findTool())
    }

    internal fun layout(width: Int, height: Int, vkFormat: Int): AstcNative.Layout {
        require(vkFormat in 157..184) { "Not an ASTC UNORM/SRGB format: $vkFormat" }
        val (blockWidth, blockHeight) = footprints[(vkFormat - 157) / 2]
        return AstcNative.Layout(width, height, blockWidth, blockHeight, (vkFormat - 157) % 2 == 1)
    }

    internal fun decodeWithTool(blocks: ByteArray, layout: AstcNative.Layout, tool: String): ByteArray {
        val dir = Files.createTempDirectory("prism-kap-astc").toFile()
        try {
            val astc = File(dir, "layer.astc")
            astc.writeBytes(astcFile(blocks, layout))
            val png = File(dir, "layer.png")
            val process = try {
                ProcessBuilder(tool, if (layout.srgb) "-ds" else "-dl", astc.absolutePath, png.absolutePath, "-j", "1")
                    .redirectErrorStream(true)
                    .start()
            } catch (failure: IOException) {
                throw IllegalStateException("ASTC native library unavailable and cannot start astcenc tool '$tool'", failure)
                    .also { AstcNative.loadFailure?.let(it::addSuppressed) }
            }
            try {
                // 持续排空输出但只保留尾部诊断，避免管道堵塞及无界内存。 / Drain output, retaining only bounded diagnostics.
                val output = StringBuilder()
                process.inputStream.bufferedReader().use { reader ->
                    val chunk = CharArray(1024)
                    while (true) {
                        val count = reader.read(chunk)
                        if (count < 0) break
                        output.append(chunk, 0, count)
                        if (output.length > 4096) output.delete(0, output.length - 4096)
                    }
                }
                val exit = process.waitFor()
                check(exit == 0) { "astcenc failed (exit $exit): ${output.toString().trim()}" }
                val image = ImageIO.read(png) ?: error("Cannot read astcenc output PNG")
                check(image.width == layout.width && image.height == layout.height) {
                    "astcenc output dimensions ${image.width}x${image.height}; expected ${layout.width}x${layout.height}"
                }
                return toRgba(image)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while waiting for astcenc", failure)
            } finally {
                process.destroyForcibly()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** ASTC 头存实际宽、高、深度（24 位小端），不是减一。 / The ASTC header stores actual dimensions, not dimensions minus one. */
    internal fun astcFile(blocks: ByteArray, layout: AstcNative.Layout): ByteArray {
        layout.validate(blocks)
        val header = ByteArray(16)
        header[0] = 0x13
        header[1] = 0xAB.toByte()
        header[2] = 0xA1.toByte()
        header[3] = 0x5C
        header[4] = layout.blockWidth.toByte()
        header[5] = layout.blockHeight.toByte()
        header[6] = 1
        putU24(header, 7, layout.width)
        putU24(header, 10, layout.height)
        putU24(header, 13, 1)
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

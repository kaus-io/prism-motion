package com.zxhhyj.prism.motion.clinative

import com.zxhhyj.prism.motion.format.Ktx2
import com.zxhhyj.prism.motion.format.Ktx2Format
import com.zxhhyj.prism.motion.format.Mp4
import com.zxhhyj.prism.motion.probe.AnimationProbe
import com.zxhhyj.prism.motion.utils.err
import com.zxhhyj.prism.motion.utils.Platform

/** pack vap / pack kap：用内嵌（或外部）ffmpeg、astcenc 完成帧序列打包。 */
internal object PackCmd {

    fun run(kind: String, options: Map<String, String>, asJson: Boolean): Int {
        val framesDir = options["frames"]
        val out = options["out"]
        if (framesDir == null || out == null) {
            return fail("pack $kind 需要 --frames <dir> 与 --out <file>", asJson, EXIT_USAGE)
        }
        if (!Platform.exists(framesDir) || !Platform.isDirectory(framesDir)) {
            return fail("帧目录不存在: $framesDir", asJson, EXIT_USAGE)
        }
        val fps = options["fps"]?.toIntOrNull() ?: 30
        if (fps <= 0) return fail("--fps 必须为正数", asJson, EXIT_USAGE)
        out.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(Platform::mkdirs)

        return try {
            when (kind) {
                "vap" -> packVap(framesDir, out, fps, options, asJson)
                "kap" -> packKap(framesDir, out, fps, options, asJson)
                else -> fail("未知格式: $kind（只有 vap / kap）", asJson, EXIT_USAGE)
            }
        } catch (e: Exception) {
            fail("打包失败: ${e.message ?: e.toString()}", asJson, EXIT_RUNTIME)
        }
    }

    private fun packVap(
        framesDir: String,
        out: String,
        fps: Int,
        options: Map<String, String>,
        asJson: Boolean,
    ): Int {
        val files = pngFiles(framesDir)
        if (files.isEmpty()) return fail("$framesDir 里没有 PNG", asJson, EXIT_USAGE)
        val first = ImageDecoder.decodePng(Platform.readFile(files.first()))
        val width = first.width
        val height = first.height

        val sources = ArrayList<Frame>(files.size)
        sources += first
        for (index in 1 until files.size) {
            val frame = ImageDecoder.decodePng(Platform.readFile(files[index]))
            if (frame.width != width || frame.height != height) {
                return fail("帧尺寸不一致：${files[index]} 是 ${frame.width}x${frame.height}", asJson, EXIT_RUNTIME)
            }
            sources += frame
        }

        val alphaMode = (options["alpha"] ?: "auto").lowercase()
        val hasAlpha = when (alphaMode) {
            "on" -> true
            "off" -> false
            "auto" -> sources.any(::hasTransparentPixel)
            else -> return fail("--alpha 只接受 auto / on / off", asJson, EXIT_USAGE)
        }
        val scale = options["scale"]?.toFloatOrNull() ?: 0.5f
        val layout = VapPack.computeLayout(width, height, scale, hasAlpha)

        val canvasBytes = layout.videoW * layout.videoH * 4
        val frames = ArrayList<Frame>(sources.size)
        val canvas = ByteArray(canvasBytes)
        for (source in sources) {
            VapPack.packFrame(source.rgba, width, height, layout, canvas)
            frames += Frame(layout.videoW, layout.videoH, canvas.copyOf())
        }

        val bitrate = options["bitrate"]?.toIntOrNull() ?: 3000
        val stream = H264Encoder.encode(layout.videoW, layout.videoH, fps, bitrate, frames)
        val avc = AvcStream(stream.sps, stream.pps, stream.samples, stream.keyFrameIndices)
        val mp4 = Mp4Writer.write(layout.videoW, layout.videoH, fps, avc)

        val vapc = VapPack.vapcJson(frames.size, width, height, layout, fps).encodeToByteArray()
        Platform.writeFile(out, Mp4.insertTopLevel(mp4, Mp4.box("vapc", vapc)))
        val sidecar = "${out.substringBeforeLast('.')}.vapc.json"
        Platform.writeFile(sidecar, vapc)

        return report("vap", out, sidecar, frames.size, fps, asJson)
    }

    private fun hasTransparentPixel(frame: Frame): Boolean {
        var offset = 3
        while (offset < frame.rgba.size) {
            if ((frame.rgba[offset].toInt() and 0xFF) != 255) return true
            offset += 4
        }
        return false
    }

    private fun packKap(
        framesDir: String,
        out: String,
        fps: Int,
        options: Map<String, String>,
        asJson: Boolean,
    ): Int {
        val format = when ((options["format"] ?: "rgba8").lowercase()) {
            "rgba8" -> Ktx2Format.RGBA8
            "astc-4x4" -> Ktx2Format.ASTC_4X4
            "astc-6x6" -> Ktx2Format.ASTC_6X6
            "astc-8x8" -> Ktx2Format.ASTC_8X8
            else -> return fail("--format 只接受 rgba8 / astc-4x4 / astc-6x6 / astc-8x8", asJson, EXIT_USAGE)
        }
        val files = pngFiles(framesDir)
        if (files.isEmpty()) return fail("$framesDir 里没有 PNG", asJson, EXIT_USAGE)
        val (width, height) = Png.size(Platform.readFile(files.first()))
            ?: return fail("第一帧不是有效 PNG", asJson, EXIT_RUNTIME)

        val premultiply = options["premultiply"]?.toBooleanStrictOrNull() ?: true
        val tmp = Platform.makeTempDir("prism-motion-kap")
        try {
            val layers = ArrayList<ByteArray>()
            if (format == Ktx2Format.RGBA8) {
                for (file in files) {
                    val frame = ImageDecoder.decodePng(Platform.readFile(file))
                    if (frame.width != width || frame.height != height) {
                        return fail("帧尺寸不一致：$file 是 ${frame.width}x${frame.height}", asJson, EXIT_RUNTIME)
                    }
                    val layer = frame.rgba.copyOf()
                    if (premultiply) premultiplyAlpha(layer)
                    layers += layer
                }
            } else {
                val astcenc = options["astcenc"] ?: Tools.resolve("astcenc")
                val block = when (format) {
                    Ktx2Format.ASTC_4X4 -> "4x4"
                    Ktx2Format.ASTC_6X6 -> "6x6"
                    else -> "8x8"
                }
                val expected = format.layerSize(width, height)
                for ((index, file) in files.withIndex()) {
                    val astcPath = "$tmp/frame_$index.astc"
                    val code = Platform.runProcess(
                        listOf(astcenc, "-cl", file, astcPath, block, "-medium", "-silent"),
                    )
                    if (code != 0) return fail("astcenc 失败（退出码 $code）", asJson, EXIT_RUNTIME)
                    val astc = Platform.readFile(astcPath)
                    if (astc.size < 16) return fail("astcenc 输出为空", asJson, EXIT_RUNTIME)
                    val blocks = astc.copyOfRange(16, astc.size)
                    if (blocks.size != expected) {
                        return fail("ASTC 数据 ${blocks.size} 字节，期望 $expected", asJson, EXIT_RUNTIME)
                    }
                    layers += blocks
                }
            }

            val keyValues = mapOf(
                "anim.fps" to fps.toString(),
                "anim.loop" to (options["loop"]?.toBooleanStrictOrNull() ?: true).toString(),
                "anim.premultiplied" to premultiply.toString(),
            )
            val bytes = Ktx2.write(format, width, height, layers, keyValues)
            Platform.writeFile(out, bytes)
            return report("kap", out, null, files.size, fps, asJson)
        } finally {
            Platform.removeRecursively(tmp)
        }
    }

    private fun scanAlpha(raw: ByteArray, frameBytes: Int): Boolean {
        var frame = 0
        while (frame * frameBytes < raw.size) {
            val base = frame * frameBytes
            var offset = 3
            while (offset < frameBytes) {
                if ((raw[base + offset].toInt() and 0xFF) != 255) return true
                offset += 4
            }
            frame++
        }
        return false
    }

    private fun premultiplyAlpha(layer: ByteArray) {
        var offset = 0
        while (offset + 3 < layer.size) {
            val alpha = layer[offset + 3].toInt() and 0xFF
            for (channel in 0 until 3) {
                val value = layer[offset + channel].toInt() and 0xFF
                layer[offset + channel] = ((value * alpha) / 255).toByte()
            }
            offset += 4
        }
    }

    private fun pngFiles(dir: String): List<String> =
        Platform.listDirectory(dir)
            .filter { it.endsWith(".png", ignoreCase = true) }
            .sortedBy { naturalKey(it) }
            .map { "$dir/$it" }

    private fun naturalKey(name: String): Long =
        name.substringBeforeLast('.').filter { it.isDigit() }.toLongOrNull() ?: Long.MAX_VALUE

    private fun report(kind: String, out: String, sidecar: String?, frames: Int, fps: Int, asJson: Boolean): Int {
        val probe = AnimationProbe.probe(out)
        if (asJson) {
            println(
                Json.obj(
                    "ok" to Json.bool(true),
                    "command" to Json.str("pack"),
                    "kind" to Json.str(kind),
                    "out" to Json.str(out),
                    "sidecar" to Json.str(sidecar),
                    "bytes" to Json.num(probe.bytes),
                    "frameCount" to Json.num(frames),
                    "fps" to Json.num(fps),
                    "probe" to ProbeCmd.toJson(probe),
                ),
            )
        } else {
            println("packed $out  ${probe.width}x${probe.height}  $frames f  $fps fps  ${probe.bytes} bytes")
            sidecar?.let { println("sidecar $it") }
        }
        return EXIT_OK
    }

    private fun fail(message: String, asJson: Boolean, exit: Int): Int {
        if (asJson) println(Json.obj("ok" to Json.bool(false), "error" to Json.str(message)))
        err(message)
        return exit
    }
}

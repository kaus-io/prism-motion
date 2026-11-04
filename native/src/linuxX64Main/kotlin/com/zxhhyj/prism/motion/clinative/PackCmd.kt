package com.zxhhyj.prism.motion.clinative

import com.zxhhyj.prism.motion.probe.AnimationProbe

/** pack vap / pack kap：用内嵌（或外部）ffmpeg、astcenc 完成帧序列打包。 */
internal object PackCmd {

    fun run(kind: String, options: Map<String, String>, asJson: Boolean): Int {
        val framesDir = options["frames"]
        val out = options["out"]
        if (framesDir == null || out == null) {
            return fail("pack $kind 需要 --frames <dir> 与 --out <file>", asJson, EXIT_USAGE)
        }
        if (!Posix.exists(framesDir) || !Posix.isDirectory(framesDir)) {
            return fail("帧目录不存在: $framesDir", asJson, EXIT_USAGE)
        }
        val fps = options["fps"]?.toIntOrNull() ?: 30
        if (fps <= 0) return fail("--fps 必须为正数", asJson, EXIT_USAGE)
        out.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(Posix::mkdirs)

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
        val (width, height) = Png.size(Posix.readFile(files.first()))
            ?: return fail("第一帧不是有效 PNG", asJson, EXIT_RUNTIME)

        val ffmpeg = options["ffmpeg"] ?: Tools.resolve("ffmpeg")
        val tmp = Posix.makeTempDir("prism-motion-vap")
        try {
            val rawPath = "$tmp/frames.rgba"
            val decodeCode = Posix.runProcess(
                listOf(
                    ffmpeg, "-y", "-nostdin", "-pattern_type", "glob", "-i", "$framesDir/*.png",
                    "-frames:v", files.size.toString(), "-pix_fmt", "rgba", "-f", "rawvideo", rawPath,
                ),
            )
            if (decodeCode != 0) return fail("ffmpeg 解码失败（退出码 $decodeCode）", asJson, EXIT_RUNTIME)
            val raw = Posix.readFile(rawPath)
            val frameBytes = width * height * 4
            if (raw.size != frameBytes * files.size) {
                return fail("解码帧数不符：期望 ${files.size} 帧，实际 ${raw.size / frameBytes} 帧", asJson, EXIT_RUNTIME)
            }

            val alphaMode = (options["alpha"] ?: "auto").lowercase()
            val hasAlpha = when (alphaMode) {
                "on" -> true
                "off" -> false
                "auto" -> scanAlpha(raw, frameBytes)
                else -> return fail("--alpha 只接受 auto / on / off", asJson, EXIT_USAGE)
            }
            val scale = options["scale"]?.toFloatOrNull() ?: 0.5f
            val layout = VapPack.computeLayout(width, height, scale, hasAlpha)

            val canvasBytes = layout.videoW * layout.videoH * 3
            val packed = ByteArray(canvasBytes * files.size)
            val frame = ByteArray(canvasBytes)
            for (index in files.indices) {
                VapPack.packFrame(raw, width, height, layout, frame)
                frame.copyInto(packed, index * canvasBytes)
            }
            val packedPath = "$tmp/packed.rgb"
            Posix.writeFile(packedPath, packed)

            val encodeCode = Posix.runProcess(
                listOf(
                    ffmpeg, "-y", "-nostdin", "-f", "rawvideo", "-pix_fmt", "rgb24",
                    "-s", "${layout.videoW}x${layout.videoH}", "-framerate", fps.toString(),
                    "-i", packedPath, "-vcodec", "libx265", "-b:v", "3000k",
                    "-profile:v", "main", "-level", "4.0", "-tag:v", "hvc1",
                    "-pix_fmt", "yuv420p", "$tmp/video.mp4",
                ),
            )
            if (encodeCode != 0) return fail("ffmpeg 编码失败（退出码 $encodeCode）", asJson, EXIT_RUNTIME)

            val vapc = VapPack.vapcJson(files.size, width, height, layout, fps).encodeToByteArray()
            val mp4 = VapPack.insertBox(Posix.readFile("$tmp/video.mp4"), VapPack.boxBytes("vapc", vapc))
            Posix.writeFile(out, mp4)
            val sidecar = "${out.substringBeforeLast('.')}.vapc.json"
            Posix.writeFile(sidecar, vapc)

            return report("vap", out, sidecar, files.size, fps, asJson)
        } finally {
            Posix.removeRecursively(tmp)
        }
    }

    private fun packKap(
        framesDir: String,
        out: String,
        fps: Int,
        options: Map<String, String>,
        asJson: Boolean,
    ): Int {
        val format = when ((options["format"] ?: "rgba8").lowercase()) {
            "rgba8" -> KtxFormat.RGBA8
            "astc-4x4" -> KtxFormat.ASTC_4X4
            "astc-6x6" -> KtxFormat.ASTC_6X6
            "astc-8x8" -> KtxFormat.ASTC_8X8
            else -> return fail("--format 只接受 rgba8 / astc-4x4 / astc-6x6 / astc-8x8", asJson, EXIT_USAGE)
        }
        val files = pngFiles(framesDir)
        if (files.isEmpty()) return fail("$framesDir 里没有 PNG", asJson, EXIT_USAGE)
        val (width, height) = Png.size(Posix.readFile(files.first()))
            ?: return fail("第一帧不是有效 PNG", asJson, EXIT_RUNTIME)

        val premultiply = options["premultiply"]?.toBooleanStrictOrNull() ?: true
        val tmp = Posix.makeTempDir("prism-motion-kap")
        try {
            val layers = ArrayList<ByteArray>()
            if (format == KtxFormat.RGBA8) {
                val ffmpeg = options["ffmpeg"] ?: Tools.resolve("ffmpeg")
                val rawPath = "$tmp/frames.rgba"
                val code = Posix.runProcess(
                    listOf(
                        ffmpeg, "-y", "-nostdin", "-pattern_type", "glob", "-i", "$framesDir/*.png",
                        "-frames:v", files.size.toString(), "-pix_fmt", "rgba", "-f", "rawvideo", rawPath,
                    ),
                )
                if (code != 0) return fail("ffmpeg 解码失败（退出码 $code）", asJson, EXIT_RUNTIME)
                val raw = Posix.readFile(rawPath)
                val frameBytes = width * height * 4
                if (raw.size != frameBytes * files.size) {
                    return fail("解码帧数不符：期望 ${files.size} 帧", asJson, EXIT_RUNTIME)
                }
                for (index in files.indices) {
                    val layer = raw.copyOfRange(index * frameBytes, (index + 1) * frameBytes)
                    if (premultiply) premultiplyAlpha(layer)
                    layers += layer
                }
            } else {
                val astcenc = options["astcenc"] ?: Tools.resolve("astcenc")
                val block = when (format) {
                    KtxFormat.ASTC_4X4 -> "4x4"
                    KtxFormat.ASTC_6X6 -> "6x6"
                    else -> "8x8"
                }
                val expected = format.layerSize(width, height)
                for ((index, file) in files.withIndex()) {
                    val astcPath = "$tmp/frame_$index.astc"
                    val code = Posix.runProcess(
                        listOf(astcenc, "-cl", file, astcPath, block, "-medium", "-silent"),
                    )
                    if (code != 0) return fail("astcenc 失败（退出码 $code）", asJson, EXIT_RUNTIME)
                    val astc = Posix.readFile(astcPath)
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
            Posix.writeFile(out, bytes)
            return report("kap", out, null, files.size, fps, asJson)
        } finally {
            Posix.removeRecursively(tmp)
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
        Posix.listDirectory(dir)
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

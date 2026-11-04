package com.zxhhyj.prism.motion.cli

import com.zxhhyj.ktxanim.core.KtxFormat
import com.zxhhyj.ktxanim.encode.AstcQuality
import com.zxhhyj.ktxanim.encode.KtxEncodeException
import com.zxhhyj.ktxanim.encode.KtxEncodeRequest
import com.zxhhyj.ktxanim.encode.KtxEncoder
import com.zxhhyj.prism.motion.probe.AnimationProbe
import com.zxhhyj.vap.encode.DefaultVapEncoder
import com.zxhhyj.vap.encode.EncodeProgress
import com.zxhhyj.vap.encode.EncodeRequest
import com.zxhhyj.vap.encode.EncodeResult
import com.zxhhyj.vap.encode.HasAlpha
import com.zxhhyj.vap.encode.Quality
import com.zxhhyj.vap.encode.VideoCodec
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * 图片帧 → VAP / KAP。
 *
 * VAP 需要 `ffmpeg`（H.264 编码）；KAP 的 RGBA8 不需要外部工具，
 * ASTC 格式还需要 `astcenc`。
 */
internal object PackCli {

    fun run(kind: String, options: Map<String, String>, asJson: Boolean): Int {
        val frames = options["frames"]?.let(Paths::get)
        val out = options["out"]?.let(Paths::get)
        if (frames == null || out == null) {
            return fail("pack $kind 需要 --frames <dir> 与 --out <file>", asJson, EXIT_USAGE)
        }
        if (!Files.isDirectory(frames)) {
            return fail("帧目录不存在: $frames", asJson, EXIT_USAGE)
        }
        val fps = options["fps"]?.toIntOrNull() ?: 30
        if (fps <= 0) return fail("--fps 必须为正数", asJson, EXIT_USAGE)
        Files.createDirectories(out.toAbsolutePath().parent)

        return when (kind) {
            "vap" -> packVap(frames, out, fps, options, asJson)
            "kap" -> packKap(frames, out, fps, options, asJson)
            else -> fail("未知格式: $kind（只有 vap / kap）", asJson, EXIT_USAGE)
        }
    }

    private fun packVap(
        frames: Path,
        out: Path,
        fps: Int,
        options: Map<String, String>,
        asJson: Boolean,
    ): Int {
        val alpha = when (options["alpha"]?.lowercase() ?: "auto") {
            "auto" -> HasAlpha.Auto
            "on" -> HasAlpha.On
            "off" -> HasAlpha.Off
            else -> return fail("--alpha 只接受 auto / on / off", asJson, EXIT_USAGE)
        }
        val request = EncodeRequest(
            inputDir = frames.toString(),
            fps = fps,
            scale = options["scale"]?.toFloatOrNull() ?: 0.5f,
            codec = VideoCodec.H265,
            quality = Quality.Bitrate(3000),
            ffmpegPath = options["ffmpeg"] ?: "ffmpeg",
            version = 3,
            hasAlpha = alpha,
            outputDir = Files.createTempDirectory("prism-motion-vap").toString(),
        )

        var result: EncodeResult? = null
        var failure: String? = null
        try {
            runBlocking {
                DefaultVapEncoder().encode(request).collect { event ->
                    when (event) {
                        is EncodeProgress.Success -> result = event.result
                        is EncodeProgress.Failed -> failure = event.message
                        is EncodeProgress.Warning -> System.err.println("warning: ${event.message}")
                        is EncodeProgress.Running -> Unit
                    }
                }
            }
        } catch (e: Exception) {
            return fail("VAP 编码失败: ${e.message}", asJson, EXIT_RUNTIME)
        }

        val encoded = result ?: return fail(
            "VAP 编码失败: ${failure ?: "未知原因"}（需要 ffmpeg 在 PATH 里，或用 --ffmpeg 指定）",
            asJson,
            EXIT_RUNTIME,
        )

        val video = Paths.get(encoded.videoPath)
        Files.move(video, out, StandardCopyOption.REPLACE_EXISTING)
        val sidecar = out.toAbsolutePath().parent.resolve("${out.fileName.toString().substringBeforeLast('.')}.vapc.json")
        runCatching {
            Files.move(Paths.get(encoded.vapcJsonPath), sidecar, StandardCopyOption.REPLACE_EXISTING)
        }

        return report("vap", out, sidecar, encoded.config.totalFrames, fps, asJson)
    }

    private fun packKap(
        frames: Path,
        out: Path,
        fps: Int,
        options: Map<String, String>,
        asJson: Boolean,
    ): Int {
        val format = when (options["format"]?.lowercase() ?: "rgba8") {
            "rgba8" -> KtxFormat.RGBA8
            "astc-4x4" -> KtxFormat.ASTC_4X4
            "astc-6x6" -> KtxFormat.ASTC_6X6
            "astc-8x8" -> KtxFormat.ASTC_8X8
            else -> return fail("--format 只接受 rgba8 / astc-4x4 / astc-6x6 / astc-8x8", asJson, EXIT_USAGE)
        }
        val request = KtxEncodeRequest(
            input = frames,
            output = out,
            fps = fps,
            format = format,
            quality = AstcQuality.MEDIUM,
            premultiply = options["premultiply"]?.toBooleanStrictOrNull() ?: true,
            loop = options["loop"]?.toBooleanStrictOrNull() ?: true,
            ffmpeg = options["ffmpeg"] ?: "ffmpeg",
            astcenc = options["astcenc"] ?: "astcenc",
        )
        return try {
            val result = KtxEncoder().encode(request)
            report("kap", out, null, result.frameCount, fps, asJson)
        } catch (e: KtxEncodeException) {
            fail("KAP 打包失败: ${e.message}", asJson, EXIT_RUNTIME)
        } catch (e: Exception) {
            fail("KAP 打包失败: ${e.message}", asJson, EXIT_RUNTIME)
        }
    }

    private fun report(
        kind: String,
        out: Path,
        sidecar: Path?,
        frames: Int,
        fps: Int,
        asJson: Boolean,
    ): Int {
        val probe = AnimationProbe.probe(out)
        if (asJson) {
            println(
                Json.obj(
                    "ok" to Json.bool(true),
                    "command" to Json.str("pack"),
                    "kind" to Json.str(kind),
                    "out" to Json.str(out.toString()),
                    "sidecar" to Json.str(sidecar?.toString()),
                    "bytes" to Json.num(probe.bytes),
                    "frameCount" to Json.num(frames),
                    "fps" to Json.num(fps),
                    "probe" to ProbeCli.resultJson(probe),
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
        System.err.println(message)
        return exit
    }
}

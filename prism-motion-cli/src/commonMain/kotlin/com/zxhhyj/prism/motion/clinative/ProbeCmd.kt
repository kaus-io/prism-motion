package com.zxhhyj.prism.motion.clinative

import com.zxhhyj.prism.motion.PrismMotionFormat
import com.zxhhyj.prism.motion.format.PrismPayloadKind
import com.zxhhyj.prism.motion.probe.PrismMotionProbe
import com.zxhhyj.prism.motion.probe.ProbeIssue
import com.zxhhyj.prism.motion.probe.ProbeResult
import com.zxhhyj.prism.motion.probe.ProbeSeverity
import com.zxhhyj.prism.motion.utils.err
import com.zxhhyj.prism.motion.utils.Platform

/** engines / probe / check 三个只读命令。 / Read-only engines, probe and check commands. */
internal object ProbeCmd {

    private data class EngineInfo(
        val id: String,
        val format: PrismMotionFormat,
        val payloadKind: PrismPayloadKind,
        val decode: List<String>,
        val encode: List<String>,
    )

    private val ENGINES = listOf(
        EngineInfo("Vap", PrismMotionFormat.Prism, PrismPayloadKind.Vap, decode = listOf("android"), encode = listOf("linuxX64")),
        EngineInfo("Kap", PrismMotionFormat.Prism, PrismPayloadKind.Ktx2, decode = listOf("android"), encode = listOf("linuxX64")),
    )

    fun engines(asJson: Boolean): Int {
        if (asJson) {
            val body = Json.arr(
                ENGINES.map {
                    Json.obj(
                        "id" to Json.str(it.id),
                        "format" to Json.str(it.format.name),
                        "suffix" to Json.str(it.format.suffix),
                        "payloadKind" to Json.str(it.payloadKind.name),
                        "decode" to Json.arr(it.decode.map(Json::str)),
                        "encode" to Json.arr(it.encode.map(Json::str)),
                    )
                },
            )
            val tools = Json.obj(Tools.describe().mapValues { Json.str(it.value) })
            println(
                Json.obj(
                    "ok" to Json.bool(true),
                    "command" to Json.str("engines"),
                    "engines" to body,
                    "tools" to tools,
                ),
            )
            return EXIT_OK
        }
        for (engine in ENGINES) {
            println("${engine.id} ${engine.format.suffix} decode=${engine.decode.joinToString(",")} encode=${engine.encode.joinToString(",")}")
        }
        for ((name, path) in Tools.describe()) println("tool $name=$path")
        return EXIT_OK
    }

    fun probe(paths: List<String>, asJson: Boolean): Int {
        val files = expand(paths) ?: return EXIT_USAGE
        if (files.isEmpty()) {
            err("没有可探测的文件")
            return EXIT_USAGE
        }
        val results = files.map(::safeProbe)
        val ok = results.all { it.ok }
        if (asJson) {
            println(
                Json.obj(
                    "ok" to Json.bool(ok),
                    "command" to Json.str("probe"),
                    "results" to Json.arr(results.map(::toJson)),
                ),
            )
        } else {
            results.forEach(::printHuman)
        }
        return if (ok) EXIT_OK else EXIT_VALIDATION
    }

    fun check(paths: List<String>, asJson: Boolean): Int {
        val files = expand(paths) ?: return EXIT_USAGE
        if (files.isEmpty()) {
            err("没有可检查的文件")
            return EXIT_USAGE
        }
        val results = files.map(::checkOne)
        val failed = results.filterNot { it.ok }
        if (asJson) {
            println(
                Json.obj(
                    "ok" to Json.bool(failed.isEmpty()),
                    "command" to Json.str("check"),
                    "checked" to Json.num(results.size),
                    "failed" to Json.num(failed.size),
                    "results" to Json.arr(failed.map(::toJson)),
                ),
            )
        } else {
            failed.forEach(::printHuman)
            println("checked=${results.size} failed=${failed.size}")
        }
        return if (failed.isEmpty()) EXIT_OK else EXIT_VALIDATION
    }

    private fun checkOne(path: String): ProbeResult {
        val result = safeProbe(path)
        if (PrismMotionFormat.ofFileName(fileName(path)) == null) {
            return result.copy(
                issues = result.issues + ProbeIssue(
                    ProbeSeverity.Error,
                    "unsupported-format",
                    "后缀不认识，只支持 ${PrismMotionFormat.entries.joinToString("/") { it.suffix }}",
                ),
            )
        }
        return result
    }

    private fun expand(paths: List<String>): List<String>? {
        val out = ArrayList<String>()
        for (raw in paths) {
            when {
                !Platform.exists(raw) -> {
                    err("路径不存在: $raw")
                    return null
                }
                Platform.isDirectory(raw) -> walk(raw, out)
                else -> out += raw
            }
        }
        return out.sorted()
    }

    private fun walk(root: String, out: MutableList<String>) {
        for (name in Platform.listDirectory(root)) {
            val path = "$root/$name"
            if (Platform.isDirectory(path)) {
                walk(path, out)
            } else if (PrismMotionFormat.ofFileName(name) != null || name.substringAfterLast('.', "").lowercase() in setOf("mp4", "ktx", "ktx2")) {
                out += path
            }
        }
    }

    private fun safeProbe(path: String): ProbeResult = runCatching { PrismMotionProbe.probe(path) }.getOrElse { error ->
        ProbeResult(
            fileName = fileName(path),
            format = null,
            bytes = 0,
            sha256 = "",
            issues = listOf(ProbeIssue(ProbeSeverity.Error, "io-error", error.message ?: error.toString())),
        )
    }

    private fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    private fun engineLabel(kind: PrismPayloadKind?): String? = when (kind) {
        PrismPayloadKind.Vap -> "Vap"
        PrismPayloadKind.Ktx2 -> "Kap"
        null -> null
    }

    private fun printHuman(result: ProbeResult) {
        val format = result.format?.name?.lowercase() ?: "unknown"
        val size = result.width?.let { "$it x ${result.height}" } ?: "-"
        val fps = result.fpsNumerator?.let { "$it/${result.fpsDenominator} fps" } ?: "-"
        val engine = engineLabel(result.payloadKind) ?: "unknown"
        val frames = result.frameCount?.let { "$it f" } ?: "-"
        val duration = result.durationMs?.let { "${it}ms" } ?: "-"
        println("${result.fileName}  $format/$engine  $size  $fps  $frames  $duration  ${if (result.ok) "ok" else "FAILED"}")
        for (issue in result.issues) {
            println("  ${issue.severity.name.lowercase()}: ${issue.code} — ${issue.message}")
        }
    }

    internal fun toJson(result: ProbeResult): String = Json.obj(
        "fileName" to Json.str(result.fileName),
        "format" to Json.str(result.format?.name),
        "payloadKind" to Json.str(result.payloadKind?.name),
        "engine" to Json.str(engineLabel(result.payloadKind)),
        "ok" to Json.bool(result.ok),
        "bytes" to Json.num(result.bytes),
        "sha256" to Json.str(result.sha256),
        "width" to Json.num(result.width),
        "height" to Json.num(result.height),
        "durationMs" to Json.num(result.durationMs),
        "frameCount" to Json.num(result.frameCount),
        "fps" to Json.num(result.fps),
        "fpsNumerator" to Json.num(result.fpsNumerator),
        "fpsDenominator" to Json.num(result.fpsDenominator),
        "alphaMode" to Json.str(result.alphaMode?.name),
        "hasAlpha" to Json.bool(result.hasAlpha),
        "metadata" to Json.obj(result.metadata),
        "issues" to Json.arr(
            result.issues.map {
                Json.obj(
                    "severity" to Json.str(it.severity.name.lowercase()),
                    "code" to Json.str(it.code),
                    "message" to Json.str(it.message),
                )
            },
        ),
    )
}

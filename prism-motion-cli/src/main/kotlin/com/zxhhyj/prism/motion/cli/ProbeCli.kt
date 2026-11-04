package com.zxhhyj.prism.motion.cli

import com.zxhhyj.prism.motion.AnimationFormat
import com.zxhhyj.prism.motion.probe.AnimationProbe
import com.zxhhyj.prism.motion.probe.ProbeIssue
import com.zxhhyj.prism.motion.probe.ProbeResult
import com.zxhhyj.prism.motion.probe.ProbeSeverity
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** engines / probe / check 三个只读命令。 */
internal object ProbeCli {

    private data class EngineInfo(
        val id: String,
        val format: AnimationFormat,
        val decode: List<String>,
        val encode: List<String>,
    )

    private val ENGINES = listOf(
        EngineInfo("Vap", AnimationFormat.Vap, decode = listOf("android"), encode = listOf("jvm")),
        EngineInfo("Kap", AnimationFormat.Kap, decode = listOf("android"), encode = listOf("jvm")),
    )

    fun engines(asJson: Boolean): Int {
        if (asJson) {
            val body = Json.arr(
                ENGINES.map {
                    Json.obj(
                        "id" to Json.str(it.id),
                        "format" to Json.str(it.format.name),
                        "suffix" to Json.str(it.format.suffix),
                        "decode" to Json.arr(it.decode.map(Json::str)),
                        "encode" to Json.arr(it.encode.map(Json::str)),
                    )
                },
            )
            println(Json.obj("ok" to Json.bool(true), "command" to Json.str("engines"), "engines" to body))
            return EXIT_OK
        }
        for (engine in ENGINES) {
            println(
                "%-4s %-6s decode=%-7s encode=%s".format(
                    engine.id,
                    engine.format.suffix,
                    engine.decode.joinToString(","),
                    engine.encode.joinToString(","),
                ),
            )
        }
        return EXIT_OK
    }

    fun probe(paths: List<String>, asJson: Boolean): Int {
        val files = expand(paths) ?: return EXIT_USAGE
        if (files.isEmpty()) {
            System.err.println("没有可探测的文件")
            return EXIT_USAGE
        }
        val results = files.map(::safeProbe)
        val ok = results.all { it.ok }

        if (asJson) {
            println(
                Json.obj(
                    "ok" to Json.bool(ok),
                    "command" to Json.str("probe"),
                    "results" to Json.arr(results.map(::resultJson)),
                ),
            )
        } else {
            for (result in results) printHuman(result)
        }
        return if (ok) EXIT_OK else EXIT_VALIDATION
    }

    fun check(paths: List<String>, asJson: Boolean): Int {
        val files = expand(paths) ?: return EXIT_USAGE
        if (files.isEmpty()) {
            System.err.println("没有可检查的文件")
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
                    "results" to Json.arr(results.filterNot { it.ok }.map(::resultJson)),
                ),
            )
        } else {
            for (result in failed) printHuman(result)
            println("checked=${results.size} failed=${failed.size}")
        }
        return if (failed.isEmpty()) EXIT_OK else EXIT_VALIDATION
    }

    /** check 额外要求后缀可识别；probe 允许未知格式但会报错。 */
    private fun checkOne(path: Path): ProbeResult {
        val result = AnimationProbe.probe(path.toString())
        if (AnimationFormat.ofFileName(path.name) == null) {
            return result.copy(
                issues = result.issues + ProbeIssue(
                    ProbeSeverity.Error,
                    "unsupported-format",
                    "后缀不认识，只支持 ${AnimationFormat.entries.joinToString("/") { it.suffix }}",
                ),
            )
        }
        return result
    }

    private fun expand(paths: List<String>): List<Path>? {
        val out = ArrayList<Path>()
        for (raw in paths) {
            val path = Paths.get(raw)
            when {
                !Files.exists(path) -> {
                    System.err.println("路径不存在: $raw")
                    return null
                }
                path.isDirectory() -> walk(path, out)
                else -> out.add(path)
            }
        }
        return out.sortedBy { it.toString() }
    }

    /** 目录里没有权限的子目录直接跳过，不让一个坏目录毁掉整次检查。 */
    private fun walk(root: Path, out: MutableList<Path>) {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (AnimationFormat.ofFileName(file.fileName.toString()) != null) out.add(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            },
        )
    }

    private fun safeProbe(path: Path): ProbeResult = runCatching { AnimationProbe.probe(path.toString()) }.getOrElse { e ->
        ProbeResult(
            fileName = path.name,
            format = null,
            bytes = 0,
            sha256 = "",
            issues = listOf(
                ProbeIssue(ProbeSeverity.Error, "io-error", e.message ?: e.toString()),
            ),
        )
    }

    private fun printHuman(result: ProbeResult) {
        val format = result.format?.name?.lowercase() ?: "unknown"
        val size = result.width?.let { "$it x ${result.height}" } ?: "-"
        val fps = result.fps?.let { "$it fps" } ?: "-"
        val frames = result.frameCount?.let { "$it f" } ?: "-"
        val duration = result.durationMs?.let { "${it}ms" } ?: "-"
        println("${result.fileName}  $format  $size  $fps  $frames  $duration  ${if (result.ok) "ok" else "FAILED"}")
        for (issue in result.issues) {
            println("  ${issue.severity.name.lowercase()}: ${issue.code} — ${issue.message}")
        }
    }

    internal fun resultJson(result: ProbeResult): String = Json.obj(
        "fileName" to Json.str(result.fileName),
        "format" to Json.str(result.format?.name),
        "ok" to Json.bool(result.ok),
        "bytes" to Json.num(result.bytes),
        "sha256" to Json.str(result.sha256),
        "width" to Json.num(result.width),
        "height" to Json.num(result.height),
        "durationMs" to Json.num(result.durationMs),
        "frameCount" to Json.num(result.frameCount),
        "fps" to Json.num(result.fps),
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

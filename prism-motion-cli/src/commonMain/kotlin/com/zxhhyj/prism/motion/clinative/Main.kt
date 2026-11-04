package com.zxhhyj.prism.motion.clinative

import kotlin.system.exitProcess
import com.zxhhyj.prism.motion.utils.err
import com.zxhhyj.prism.motion.utils.Platform

internal const val EXIT_OK: Int = 0
internal const val EXIT_VALIDATION: Int = 1
internal const val EXIT_USAGE: Int = 2
internal const val EXIT_RUNTIME: Int = 3

internal const val VERSION: String = "1.0.0"

fun main(args: Array<String>) {
    exitProcess(Cli.run(args.toList()))
}

internal object Cli {

    private val VALUE_OPTIONS = setOf(
        "frames", "out", "fps", "alpha", "scale", "ffmpeg", "format", "astcenc", "loop", "premultiply",
        "bitrate",
    )

    private val USAGE = """
        prism-motion $VERSION (native) — 统一动画资源工具 / animation asset tool

        用法 / usage:
          prism-motion engines [--json]
          prism-motion probe <path...> [--json]
          prism-motion check <path...> [--json]
          prism-motion pack vap --frames <dir> --out <file.mp4> [--fps 30] [--alpha on|off]
                                                    [--scale 0.5] [--ffmpeg ffmpeg]
          prism-motion pack kap --frames <dir> --out <file.ktx> [--fps 30]
                                                    [--format rgba8|astc-8x8] [--ffmpeg ffmpeg]
                                                    [--astcenc astcenc]
          prism-motion version

        退出码 / exit codes: 0 ok, 1 校验失败, 2 用法错误, 3 运行失败
    """.trimIndent()

    fun run(args: List<String>): Int {
        if (args.isEmpty()) {
            println(USAGE)
            return EXIT_USAGE
        }
        val command = args[0]
        val rest = args.drop(1)
        val asJson = rest.contains("--json")

        return when (command) {
            "help", "--help", "-h" -> {
                println(USAGE)
                EXIT_OK
            }
            "version" -> {
                if (asJson) println(Json.obj("ok" to Json.bool(true), "version" to Json.str(VERSION)))
                else println(VERSION)
                EXIT_OK
            }
            "engines" -> ProbeCmd.engines(asJson)
            "probe" -> ProbeCmd.probe(positionals(rest), asJson)
            "check" -> ProbeCmd.check(positionals(rest), asJson)
            "pack" -> {
                val parsed = parseOptions(rest)
                val kind = parsed.positional.firstOrNull()
                if (kind == null) {
                    err("pack 需要 vap 或 kap / pack requires vap or kap")
                    EXIT_USAGE
                } else {
                    PackCmd.run(kind, parsed.options, asJson)
                }
            }
            else -> {
                err("未知命令 / unknown command: $command")
                println(USAGE)
                EXIT_USAGE
            }
        }
    }

    private fun positionals(args: List<String>): List<String> = args.filterNot { it.startsWith("--") }

    internal data class Parsed(val options: Map<String, String>, val positional: List<String>)

    private fun parseOptions(args: List<String>): Parsed {
        val options = LinkedHashMap<String, String>()
        val positional = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            if (arg.startsWith("--")) {
                val name = arg.removePrefix("--")
                if (name in VALUE_OPTIONS && i + 1 < args.size) {
                    options[name] = args[i + 1]
                    i += 2
                } else {
                    i += 1
                }
            } else {
                positional += arg
                i += 1
            }
        }
        return Parsed(options, positional)
    }
}

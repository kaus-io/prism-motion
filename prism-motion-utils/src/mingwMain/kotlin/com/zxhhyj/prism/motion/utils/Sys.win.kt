package com.zxhhyj.prism.motion.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.system

/** Windows 的系统调用：文件系统已由 kotlinx-io 统一实现，这里只剩进程/环境/权限。 */
@OptIn(ExperimentalForeignApi::class)
internal actual object Sys {

    actual fun makeTempDir(prefix: String): String {
        val base = env("TEMP") ?: env("TMP") ?: "."
        var index = 0
        while (true) {
            val candidate = "$base\\$prefix-$index"
            if (!Platform.exists(candidate)) {
                Platform.mkdirs(candidate)
                return candidate
            }
            index++
        }
    }

    actual fun chmodExecutable(path: String) = Unit

    actual fun runProcess(argv: List<String>): Int {
        require(argv.isNotEmpty()) { "argv 不能为空" }
        // cmd.exe 只认一个命令行：逐项加引号，内部引号转义成两个。
        val commandLine = argv.joinToString(" ") { argument ->
            if (argument.isEmpty() || argument.any { it == ' ' || it == '\t' || it == '"' }) {
                "\"" + argument.replace("\"", "\"\"") + "\""
            } else {
                argument
            }
        }
        return system(commandLine) / 256
    }

    actual fun env(name: String): String? = getenv(name)?.toKString()

    actual val executableSuffix: String = ".exe"

    actual val pathSeparator: Char = ';'
}

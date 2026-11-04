package com.zxhhyj.prism.motion.utils

import java.io.File
import java.nio.file.Files

internal actual object Sys {

    actual fun makeTempDir(prefix: String): String =
        Files.createTempDirectory(prefix).toFile().absolutePath

    actual fun chmodExecutable(path: String) {
        File(path).setExecutable(true)
    }

    actual fun runProcess(argv: List<String>): Int {
        require(argv.isNotEmpty()) { "argv 不能为空" }
        val process = ProcessBuilder(argv).inheritIO().start()
        return process.waitFor()
    }

    actual fun env(name: String): String? = System.getenv(name)

    actual val executableSuffix: String = ""

    actual val pathSeparator: Char = File.pathSeparatorChar
}

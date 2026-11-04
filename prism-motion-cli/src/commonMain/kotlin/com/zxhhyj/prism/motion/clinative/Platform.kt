package com.zxhhyj.prism.motion.clinative

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * 文件系统统一走 kotlinx-io，各平台共用一份实现；
 * 只有进程、环境变量、权限这些系统调用留在 [Sys] 的平台 actual 里。
 */
internal object Platform {

    fun exists(path: String): Boolean = SystemFileSystem.exists(Path(path))

    fun isDirectory(path: String): Boolean =
        SystemFileSystem.metadataOrNull(Path(path))?.isDirectory == true

    fun mkdirs(path: String) {
        SystemFileSystem.createDirectories(Path(path))
    }

    fun readFile(path: String): ByteArray =
        SystemFileSystem.source(Path(path)).use { it.buffered().readByteArray() }

    fun writeFile(path: String, bytes: ByteArray) {
        // 必须关闭 buffered 包装，否则数据留在缓冲区里。
        SystemFileSystem.sink(Path(path)).buffered().use { it.write(bytes) }
    }

    fun listDirectory(path: String): List<String> =
        SystemFileSystem.list(Path(path)).map { it.name }.sorted()

    fun removeRecursively(path: String) {
        val target = Path(path)
        if (!SystemFileSystem.exists(target)) return
        if (SystemFileSystem.metadataOrNull(target)?.isDirectory == true) {
            for (child in SystemFileSystem.list(target)) removeRecursively(child.toString())
        }
        SystemFileSystem.delete(target, mustExist = false)
    }

    fun makeTempDir(prefix: String): String = Sys.makeTempDir(prefix)

    fun chmodExecutable(path: String) = Sys.chmodExecutable(path)

    fun selfExecutable(): String? = selfExecutablePath()

    fun runProcess(argv: List<String>): Int = Sys.runProcess(argv)

    fun env(name: String): String? = Sys.env(name)

    val executableSuffix: String get() = Sys.executableSuffix

    val pathSeparator: Char get() = Sys.pathSeparator
}

/** 平台相关的系统调用：posix 与 Windows 各一套。 */
internal expect object Sys {
    fun makeTempDir(prefix: String): String
    fun chmodExecutable(path: String)
    fun runProcess(argv: List<String>): Int
    fun env(name: String): String?
    val executableSuffix: String
    val pathSeparator: Char
}

/** 当前可执行文件的绝对路径；各平台实现不同（/proc、_NSGetExecutablePath、GetModuleFileName）。 */
internal expect fun selfExecutablePath(): String?

/** 错误输出走 stderr，保证 stdout 只有 JSON / 结果。 */
internal expect fun err(message: String)

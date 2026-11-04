package com.zxhhyj.prism.motion.clinative

/** 平台文件 / 进程能力；posix 与 Windows 各有一份 actual。 */
internal expect object Platform {
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun mkdirs(path: String)
    fun readFile(path: String): ByteArray
    fun writeFile(path: String, bytes: ByteArray)
    fun listDirectory(path: String): List<String>
    fun makeTempDir(prefix: String): String
    fun chmodExecutable(path: String)
    fun removeRecursively(path: String)
    fun selfExecutable(): String?
    fun runProcess(argv: List<String>): Int
    fun env(name: String): String?

    /** 可执行文件后缀：posix 为空，Windows 为 ".exe"。 */
    val executableSuffix: String

    /** PATH 分隔符：posix 为 ':'，Windows 为 ';'。 */
    val pathSeparator: Char
}

/** 当前可执行文件的绝对路径；各平台实现不同（/proc、_NSGetExecutablePath、GetModuleFileName）。 */
internal expect fun selfExecutablePath(): String?

/** 错误输出走 stderr，保证 stdout 只有 JSON / 结果。 */
internal expect fun err(message: String)

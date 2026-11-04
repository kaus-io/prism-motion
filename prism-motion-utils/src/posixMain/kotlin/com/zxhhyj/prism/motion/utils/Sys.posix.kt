package com.zxhhyj.prism.motion.utils

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKStringFromUtf8
import kotlin.random.Random
import platform.posix.S_IRGRP
import platform.posix.S_IROTH
import platform.posix.S_IRUSR
import platform.posix.S_IXGRP
import platform.posix.S_IXOTH
import platform.posix.S_IXUSR
import platform.posix.S_IWUSR
import platform.posix.chmod
import platform.posix.execvp
import platform.posix.fork
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.waitpid
import platform.posix._exit

@OptIn(ExperimentalForeignApi::class)
internal actual object Sys {

    actual fun makeTempDir(prefix: String): String {
        val base = env("TMPDIR")?.takeIf { it.isNotBlank() } ?: "/tmp"
        val unique = "${getpid()}-${Random.nextLong() and 0x7FFFFFFF}"
        val path = "$base/$prefix-$unique"
        Platform.mkdirs(path)
        return path
    }

    actual fun chmodExecutable(path: String) {
        chmod(path, (S_IRUSR or S_IWUSR or S_IXUSR or S_IRGRP or S_IXGRP or S_IROTH or S_IXOTH).toUInt())
    }

    actual fun runProcess(argv: List<String>): Int = memScoped {
        require(argv.isNotEmpty()) { "argv 不能为空" }
        val pointers = allocArray<CPointerVar<ByteVar>>(argv.size + 1)
        for ((index, arg) in argv.withIndex()) pointers[index] = arg.cstr.ptr
        pointers[argv.size] = null

        val pid = fork()
        if (pid == 0) {
            execvp(argv[0].cstr.ptr, pointers.reinterpret())
            _exit(127)
        }
        if (pid < 0) error("无法 fork 执行 ${argv[0]}")
        val status = alloc<IntVar>()
        waitpid(pid, status.ptr, 0)
        val raw = status.ptr[0]
        (raw and 0xFF00) shr 8
    }

    actual fun env(name: String): String? = getenv(name)?.toKStringFromUtf8()

    actual val executableSuffix: String = ""

    actual val pathSeparator: Char = ':'
}

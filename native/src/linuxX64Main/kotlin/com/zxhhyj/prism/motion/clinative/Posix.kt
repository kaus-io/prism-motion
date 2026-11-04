package com.zxhhyj.prism.motion.clinative

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.refTo
import kotlinx.cinterop.set
import kotlinx.cinterop.toKStringFromUtf8
import kotlinx.cinterop.usePinned
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.O_CREAT
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.S_IRGRP
import platform.posix.S_IROTH
import platform.posix.S_IXGRP
import platform.posix.S_IXOTH
import platform.posix.chmod
import platform.posix.close
import platform.posix.closedir
import platform.posix.dirent
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.fclose
import platform.posix.mkdir
import platform.posix.getpid
import kotlin.random.Random
import platform.posix.opendir
import platform.posix.execvp
import platform.posix.fork
import platform.posix._exit
import platform.posix.getenv
import kotlinx.cinterop.toKStringFromUtf8
import platform.posix.read
import platform.posix.readdir
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.unlink
import platform.posix.waitpid
import platform.posix.write
import kotlinx.cinterop.pointed

/** 原生侧需要的极简文件 / 进程封装。 */
@OptIn(ExperimentalForeignApi::class)
internal object Posix {

    fun exists(path: String): Boolean = memScoped {
        val info = alloc<stat>()
        stat(path, info.ptr) == 0
    }

    fun isDirectory(path: String): Boolean = memScoped {
        val info = alloc<stat>()
        if (stat(path, info.ptr) != 0) return false
        (info.st_mode.toInt() and 0xF000) == 0x4000
    }

    fun mkdirs(path: String) {
        val normalized = path.trimEnd('/')
        if (normalized.isEmpty() || exists(normalized)) return
        val parent = normalized.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) mkdirs(parent)
        mkdir(normalized, (S_IRUSR or S_IWUSR or S_IXUSR or S_IRGRP or S_IXGRP or S_IROTH or S_IXOTH).toUInt())
    }

    fun readFile(path: String): ByteArray {
        val file = fopen(path, "rb") ?: error("无法打开 $path")
        try {
            val chunks = ArrayList<ByteArray>()
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val readCount = buffer.usePinned { pinned ->
                    fread(pinned.addressOf(0), 1.convert(), buffer.size.convert(), file).toInt()
                }
                if (readCount <= 0) break
                chunks += buffer.copyOf(readCount)
            }
            val total = chunks.sumOf { it.size }
            val out = ByteArray(total)
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(out, offset)
                offset += chunk.size
            }
            return out
        } finally {
            fclose(file)
        }
    }

    fun writeFile(path: String, bytes: ByteArray) {
        val file = fopen(path, "wb") ?: error("无法写入 $path")
        try {
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
                }
            }
        } finally {
            fclose(file)
        }
    }

    /** 追加写，用于把原生程序自己的 payload 写回。 */
    fun appendFile(path: String, bytes: ByteArray) {
        val file = fopen(path, "ab") ?: error("无法追加 $path")
        try {
            bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file) }
        } finally {
            fclose(file)
        }
    }

    fun listDirectory(path: String): List<String> {
        val dir = opendir(path) ?: return emptyList()
        val names = ArrayList<String>()
        try {
            while (true) {
                val entry: CPointer<dirent>? = readdir(dir)
                if (entry == null) break
                @Suppress("UNCHECKED_CAST")
                val name = entry.pointed.d_name.toKStringFromUtf8()
                if (name != "." && name != "..") names += name
            }
        } finally {
            closedir(dir)
        }
        return names.sorted()
    }

    fun makeTempDir(prefix: String): String {
        val base = env("TMPDIR")?.takeIf { it.isNotBlank() } ?: "/tmp"
        val unique = "${getpid()}-${(Random.nextLong() and 0x7FFFFFFF)}"
        val path = "$base/$prefix-$unique"
        mkdirs(path)
        return path
    }

    fun chmodExecutable(path: String) {
        chmod(path, (S_IRUSR or S_IWUSR or S_IXUSR or S_IRGRP or S_IXGRP or S_IROTH or S_IXOTH).toUInt())
    }

    fun removeRecursively(path: String) {
        if (!exists(path)) return
        if (isDirectory(path)) {
            for (name in listDirectory(path)) removeRecursively("$path/$name")
            rmdir(path)
        } else {
            unlink(path)
        }
    }

    fun selfExecutable(): String? = readLink("/proc/self/exe")

    private fun readLink(path: String): String? {
        val buffer = ByteArray(4096)
        val count = buffer.usePinned { pinned ->
            platform.posix.readlink(path, pinned.addressOf(0), buffer.size.convert())
        }
        if (count <= 0) return null
        return buffer.decodeToString(0, count.toInt())
    }

    fun env(name: String): String? = getenv(name)?.toKStringFromUtf8()

    /** 阻塞执行子进程并返回退出码；stdout/stderr 直接继承。 */
    fun runProcess(argv: List<String>): Int = memScoped {
        require(argv.isNotEmpty()) { "argv 不能为空" }
        val pointers = allocArray<CPointerVar<ByteVar>>(argv.size + 1)
        for ((index, arg) in argv.withIndex()) {
            pointers[index] = arg.cstr.ptr
        }
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
}

package com.zxhhyj.prism.motion.clinative

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.getenv
import platform.posix.system
import platform.windows.CREATE_ALWAYS
import platform.windows.CloseHandle
import platform.windows.CreateDirectoryW
import platform.windows.CreateFileW
import platform.windows.DWORDVar
import platform.windows.DeleteFileW
import platform.windows.FILE_ATTRIBUTE_DIRECTORY
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.FindClose
import platform.windows.FindFirstFileW
import platform.windows.FindNextFileW
import platform.windows.GENERIC_READ
import platform.windows.GENERIC_WRITE
import platform.windows.INVALID_FILE_ATTRIBUTES
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.OPEN_EXISTING
import platform.windows.ReadFile
import platform.windows.RemoveDirectoryW
import platform.windows.WIN32_FIND_DATAW
import platform.windows.WriteFile
import platform.windows.GetFileAttributesW

/** Windows 实现：文件走 Win32 宽字符 API，子进程走 cmd.exe。 */
@OptIn(ExperimentalForeignApi::class)
internal actual object Platform {

    actual fun exists(path: String): Boolean = GetFileAttributesW(path) != INVALID_FILE_ATTRIBUTES

    actual fun isDirectory(path: String): Boolean {
        val attributes = GetFileAttributesW(path)
        if (attributes == INVALID_FILE_ATTRIBUTES) return false
        return (attributes and FILE_ATTRIBUTE_DIRECTORY.toUInt()) != 0u
    }

    actual fun mkdirs(path: String) {
        val normalized = path.trimEnd('\\', '/')
        if (normalized.isEmpty() || exists(normalized)) return
        val cut = maxOf(normalized.lastIndexOf('\\'), normalized.lastIndexOf('/'))
        if (cut > 0) mkdirs(normalized.substring(0, cut))
        CreateDirectoryW(normalized, null)
    }

    actual fun readFile(path: String): ByteArray {
        val handle = CreateFileW(
            path, GENERIC_READ.toUInt(), FILE_SHARE_READ.toUInt() or FILE_SHARE_WRITE.toUInt(), null, OPEN_EXISTING.toUInt(),
            FILE_ATTRIBUTE_NORMAL.toUInt(), null,
        )
        if (handle == INVALID_HANDLE_VALUE || handle == null) error("无法打开 $path")
        try {
            val chunks = ArrayList<ByteArray>()
            val buffer = ByteArray(1 shl 16)
            memScoped {
                val read = alloc<DWORDVar>()
                while (true) {
                    val ok = buffer.usePinned { pinned ->
                        ReadFile(handle, pinned.addressOf(0), buffer.size.convert(), read.ptr, null)
                    }
                    if (ok == 0) break
                    val count = read.ptr[0].toInt()
                    if (count <= 0) break
                    chunks += buffer.copyOf(count)
                }
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
            CloseHandle(handle)
        }
    }

    actual fun writeFile(path: String, bytes: ByteArray) {
        val handle = CreateFileW(
            path, GENERIC_WRITE.toUInt(), FILE_SHARE_READ.toUInt(), null, CREATE_ALWAYS.toUInt(),
            FILE_ATTRIBUTE_NORMAL.toUInt(), null,
        )
        if (handle == INVALID_HANDLE_VALUE || handle == null) error("无法写入 $path")
        try {
            if (bytes.isEmpty()) return
            memScoped {
                val written = alloc<DWORDVar>()
                bytes.usePinned { pinned ->
                    WriteFile(handle, pinned.addressOf(0), bytes.size.convert(), written.ptr, null)
                }
            }
        } finally {
            CloseHandle(handle)
        }
    }

    actual fun listDirectory(path: String): List<String> = memScoped {
        val data = alloc<WIN32_FIND_DATAW>()
        val handle = FindFirstFileW("$path\\*", data.ptr)
        if (handle == INVALID_HANDLE_VALUE || handle == null) return memScoped { emptyList() }
        val names = ArrayList<String>()
        try {
            while (true) {
                val name = data.cFileName.toKString()
                if (name != "." && name != "..") names += name
                if (FindNextFileW(handle, data.ptr) == 0) break
            }
        } finally {
            FindClose(handle)
        }
        names.sorted()
    }

    actual fun makeTempDir(prefix: String): String {
        val base = env("TEMP") ?: env("TMP") ?: "."
        var index = 0
        while (true) {
            val candidate = "$base\\$prefix-$index"
            if (!exists(candidate)) {
                mkdirs(candidate)
                return candidate
            }
            index++
        }
    }

    actual fun chmodExecutable(path: String) = Unit

    actual fun removeRecursively(path: String) {
        if (!exists(path)) return
        if (isDirectory(path)) {
            for (name in listDirectory(path)) removeRecursively("$path\\$name")
            RemoveDirectoryW(path)
        } else {
            DeleteFileW(path)
        }
    }

    actual fun selfExecutable(): String? = selfExecutablePath()

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

package com.zxhhyj.prism.motion.clinative

/** 外部工具查找：环境变量 → 可执行文件同级 tools/ → 缓存 → 内嵌 payload → PATH。 */
internal object Tools {

    private const val MAGIC = "PMTOOLS1"

    private fun envName(name: String): String = "PRISM_MOTION_" + name.uppercase()

    fun resolve(name: String): String = find(name) ?: name

    fun find(name: String): String? {
        Platform.env(envName(name))?.takeIf { it.isNotBlank() }?.let { return it }
        val file = name + Platform.executableSuffix

        executableDir()?.let { dir ->
            val candidate = "$dir/tools/$file"
            if (Platform.exists(candidate)) return candidate
        }

        val cached = "${cacheRoot()}/$file"
        if (Platform.exists(cached)) return cached

        extractEmbedded(file)?.let { return it }

        for (entry in Platform.env("PATH").orEmpty().split(Platform.pathSeparator)) {
            if (entry.isBlank()) continue
            val candidate = "$entry/$file"
            if (Platform.exists(candidate) && !Platform.isDirectory(candidate)) return candidate
        }
        return null
    }

    fun describe(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (name in listOf("astcenc")) out[name] = find(name) ?: "missing"
        return out
    }

    private fun executableDir(): String? = Platform.selfExecutable()?.substringBeforeLast('/', "")

    private fun cacheRoot(): String {
        val home = Platform.env("HOME") ?: Platform.env("USERPROFILE") ?: "/tmp"
        val base = Platform.env("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.cache"
        return "$base/prism-motion/tools"
    }

    /** 解析构建时追加在可执行文件末尾的 payload，并按需解压到缓存。 */
    private fun extractEmbedded(fileName: String): String? {
        val exe = Platform.selfExecutable() ?: return null
        val payload = readPayload(exe) ?: return null
        val target = "${cacheRoot()}/$fileName"
        Platform.mkdirs(cacheRoot())

        var offset = 0
        while (offset + 12 <= payload.size) {
            val nameLength = readInt(payload, offset)
            offset += 4
            if (nameLength <= 0 || offset + nameLength > payload.size) return null
            val entryName = payload.decodeToString(offset, offset + nameLength)
            offset += nameLength
            if (offset + 8 > payload.size) return null
            val dataLength = readLong(payload, offset)
            offset += 8
            if (dataLength < 0 || offset + dataLength > payload.size) return null
            if (entryName == fileName) {
                val bytes = payload.copyOfRange(offset, offset + dataLength.toInt())
                Platform.writeFile(target, bytes)
                Platform.chmodExecutable(target)
                return target
            }
            offset += dataLength.toInt()
        }
        return null
    }

    private fun readPayload(path: String): ByteArray? {
        val bytes = Platform.readFile(path)
        if (bytes.size < 24) return null
        val footer = bytes.copyOfRange(bytes.size - 24, bytes.size)
        if (footer.decodeToString(0, 8) != MAGIC) return null
        val offset = readLong(footer, 8)
        val length = readLong(footer, 16)
        if (offset < 0 || length < 0 || offset + length > bytes.size - 24) return null
        return bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until 4) value = value or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
        return value
    }

    private fun readLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        return value
    }
}

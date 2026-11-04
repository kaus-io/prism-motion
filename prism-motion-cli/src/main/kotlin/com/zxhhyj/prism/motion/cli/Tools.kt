package com.zxhhyj.prism.motion.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * 外部工具（ffmpeg / astcenc）的查找顺序：
 *
 * 1. 环境变量 `PRISM_MOTION_FFMPEG` / `PRISM_MOTION_ASTCENC`
 * 2. jar 同级的 `tools/` 目录
 * 3. 缓存目录 `~/.cache/prism-motion/tools/`
 * 4. **jar 内嵌**：构造时放进 `embedded-tools/<os>-<arch>/`，首次使用时解压到缓存
 * 5. 系统 PATH
 */
internal object Tools {

    private val toolNames = listOf("ffmpeg", "astcenc")

    private val jarDir: Path? by lazy {
        runCatching {
            val source = Tools::class.java.protectionDomain?.codeSource?.location ?: return@runCatching null
            val path = Paths.get(source.toURI())
            if (Files.isDirectory(path)) path else path.parent
        }.getOrNull()
    }

    private val cacheRoot: Path by lazy {
        val home = System.getProperty("user.home").orEmpty()
        val base = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
            ?: if (home.isNotBlank()) Files.createDirectories(Paths.get(home, ".cache")).toString() else ""
        Paths.get(base, "prism-motion", "tools", platform())
    }

    fun resolve(name: String): String = find(name)?.toString() ?: name

    fun find(name: String): Path? {
        System.getenv("PRISM_MOTION_" + name.uppercase().replace('-', '_'))
            ?.takeIf { it.isNotBlank() }
            ?.let { return Paths.get(it) }

        jarDir?.resolve("tools")?.resolve(name)?.takeIf { Files.isExecutable(it) }?.let { return it }

        val cached = cacheRoot.resolve(name)
        if (Files.isExecutable(cached)) return cached

        extractEmbedded(name)?.let { return it }

        return pathLookup(name)
    }

    /** 只报告路径，找不到时返回 null，便于 `engines` 输出真实可用性。 */
    fun describe(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (name in toolNames) {
            out[name] = find(name)?.toString() ?: "missing"
        }
        return out
    }

    private fun extractEmbedded(name: String): Path? {
        val resource = "embedded-tools/${platform()}/$name"
        val stream = Tools::class.java.classLoader.getResourceAsStream(resource) ?: return null
        val target = cacheRoot.resolve(name)
        return runCatching {
            Files.createDirectories(target.parent)
            val tmp = Files.createTempFile(target.parent, "$name-", ".tmp")
            stream.use { input -> Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING) }
            tmp.toFile().setExecutable(true)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            target
        }.getOrNull()
    }

    private fun pathLookup(name: String): Path? {
        val path = System.getenv("PATH").orEmpty()
        for (entry in path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue
            val candidate = Paths.get(entry, name)
            if (Files.isExecutable(candidate)) return candidate
        }
        return null
    }

    private fun platform(): String {
        val os = System.getProperty("os.name").lowercase()
        val osName = when {
            os.contains("linux") -> "linux"
            os.contains("mac") || os.contains("darwin") -> "darwin"
            os.contains("windows") -> "windows"
            else -> os.replace(' ', '-')
        }
        val arch = System.getProperty("os.arch").lowercase()
        val archName = when (arch) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> arch
        }
        return "$osName-$archName"
    }
}

package com.zxhhyj.prism.motion.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 外部工具（ffmpeg / astcenc）的查找顺序，保证"内嵌"和"系统安装"两种用法都能工作：
 *
 * 1. 环境变量 `PRISM_MOTION_FFMPEG` / `PRISM_MOTION_ASTCENC`
 * 2. jar 同级的 `tools/` 目录（随 jar 一起分发时用）
 * 3. `~/.cache/prism-motion/tools/`
 * 4. 直接交给 PATH
 */
internal object Tools {

    private val jarDir: Path? by lazy {
        runCatching {
            val source = Tools::class.java.protectionDomain?.codeSource?.location ?: return@runCatching null
            val path = Paths.get(source.toURI())
            if (Files.isDirectory(path)) path else path.parent
        }.getOrNull()
    }

    fun resolve(name: String): String {
        val envName = "PRISM_MOTION_" + name.uppercase().replace('-', '_')
        System.getenv(envName)?.takeIf { it.isNotBlank() }?.let { return it }

        val home = System.getProperty("user.home") ?: ""
        val candidates = listOfNotNull(
            jarDir?.resolve("tools")?.resolve(name),
            if (home.isNotBlank()) Paths.get(home, ".cache", "prism-motion", "tools", name) else null,
        )
        for (candidate in candidates) {
            if (Files.isExecutable(candidate)) return candidate.toString()
        }
        return name
    }

    /** 报告实际用到的路径，便于 agent 判断是内嵌还是系统工具。 */
    fun describe(name: String): String = resolve(name)
}

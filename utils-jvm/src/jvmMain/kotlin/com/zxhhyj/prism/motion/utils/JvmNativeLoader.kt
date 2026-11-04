package com.zxhhyj.prism.motion.utils

import java.io.File

/**
 * JVM 侧加载原生库的查找顺序：环境变量 → `~/.cache/prism-motion/native/` →
 * `thirdparty/native/<platform>/` → 交给系统动态库查找。
 * JVM native library lookup: env var → cache → repo-local build → system loader.
 */
public object JvmNativeLoader {

    /** 解析库路径；[envVar] 是可选的覆盖用环境变量名。 / Resolves the library path; [envVar] overrides when set. */
    public fun resolve(name: String, envVar: String): String {
        System.getenv(envVar)?.takeIf { it.isNotBlank() }?.let { return it }

        val fileName = System.mapLibraryName(name)
        val cache = File(System.getProperty("user.home"), ".cache/prism-motion/native/$fileName")
        if (cache.isFile) return cache.absolutePath

        val local = File("thirdparty/native/${platform()}/$fileName")
        if (local.isFile) return local.absolutePath

        return name
    }

    private fun platform(): String {
        val os = System.getProperty("os.name").lowercase()
        val osName = when {
            os.contains("linux") -> "linux"
            os.contains("mac") -> "macos"
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

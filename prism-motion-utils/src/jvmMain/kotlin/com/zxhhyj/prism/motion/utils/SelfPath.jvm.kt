package com.zxhhyj.prism.motion.utils

/** JVM 上没有"追加 payload 到可执行文件"这套机制，返回 null 表示不适用。 */
internal actual fun selfExecutablePath(): String? = null

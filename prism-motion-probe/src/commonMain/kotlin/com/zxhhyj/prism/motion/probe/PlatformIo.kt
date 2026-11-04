package com.zxhhyj.prism.motion.probe

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** 文件读取用 kotlinx-io，JVM 与各 native 目标共用一份实现。 */
internal object PlatformIo {

    fun size(path: String): Long {
        val metadata = SystemFileSystem.metadataOrNull(Path(path))
            ?: error("文件不存在: $path")
        return metadata.size
    }

    fun read(path: String): ByteArray =
        SystemFileSystem.source(Path(path)).use { it.buffered().readByteArray() }
}

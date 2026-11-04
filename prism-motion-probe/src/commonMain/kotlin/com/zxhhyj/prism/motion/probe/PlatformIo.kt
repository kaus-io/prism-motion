package com.zxhhyj.prism.motion.probe

/** 平台文件读取；只暴露探测需要的两个操作。 */
internal expect object PlatformIo {
    fun size(path: String): Long
    fun read(path: String): ByteArray
}

package com.zxhhyj.prism.motion.probe

import java.nio.file.Files
import java.nio.file.Paths

internal actual object PlatformIo {
    actual fun size(path: String): Long = Files.size(Paths.get(path))

    actual fun read(path: String): ByteArray = Files.readAllBytes(Paths.get(path))
}

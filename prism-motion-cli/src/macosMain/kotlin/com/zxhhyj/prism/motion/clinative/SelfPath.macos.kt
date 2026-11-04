package com.zxhhyj.prism.motion.clinative

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.darwin._NSGetExecutablePath
import platform.posix.UIntVar

@OptIn(ExperimentalForeignApi::class)
internal actual fun selfExecutablePath(): String? = memScoped {
    val size = alloc<UIntVar>()
    size.value = 4096u
    val buffer = ByteArray(size.value.toInt())
    val ok = buffer.usePinned { pinned -> _NSGetExecutablePath(pinned.addressOf(0), size.ptr) }
    if (ok != 0) return@memScoped null
    buffer.decodeToString(0, buffer.indexOf(0).let { if (it < 0) buffer.size else it })
}

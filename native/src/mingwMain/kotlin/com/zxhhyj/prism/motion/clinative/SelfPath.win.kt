package com.zxhhyj.prism.motion.clinative

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.windows.GetModuleFileNameW
import platform.windows.WCHARVar

@OptIn(ExperimentalForeignApi::class)
internal actual fun selfExecutablePath(): String? = memScoped {
    val buffer = allocArray<WCHARVar>(4096)
    val length = GetModuleFileNameW(null, buffer, 4096u)
    if (length == 0u) return@memScoped null
    (buffer as kotlinx.cinterop.CPointer<WCHARVar>).toKString()
}

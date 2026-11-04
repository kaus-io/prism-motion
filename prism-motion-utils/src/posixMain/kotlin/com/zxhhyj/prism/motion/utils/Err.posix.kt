package com.zxhhyj.prism.motion.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import platform.posix.fprintf
import platform.posix.stderr

@OptIn(ExperimentalForeignApi::class)
public actual fun err(message: String) {
    memScoped { fprintf(stderr, "%s\n", message.cstr.ptr) }
}

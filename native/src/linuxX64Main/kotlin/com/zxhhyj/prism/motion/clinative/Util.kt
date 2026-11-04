package com.zxhhyj.prism.motion.clinative

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import platform.posix.fprintf
import platform.posix.stderr

/** 错误输出走 stderr，保证 stdout 只有 JSON / 结果。 */
@OptIn(ExperimentalForeignApi::class)
internal fun err(message: String) {
    memScoped {
        fprintf(stderr, "%s\n", message.cstr.ptr)
    }
}

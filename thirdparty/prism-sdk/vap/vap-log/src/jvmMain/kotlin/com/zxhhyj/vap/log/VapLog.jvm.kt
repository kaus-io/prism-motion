package com.zxhhyj.vap.log

internal actual fun platformLog(level: VapLog.Level, tag: String, message: String, error: Throwable?) {
    println("[${level.name.first()}] $tag: $message")
    error?.printStackTrace()
}

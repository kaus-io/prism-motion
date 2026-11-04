package com.zxhhyj.vap.log

import android.util.Log

internal actual fun platformLog(level: VapLog.Level, tag: String, message: String, error: Throwable?) {
    val priority = when (level) {
        VapLog.Level.VERBOSE -> Log.VERBOSE
        VapLog.Level.DEBUG -> Log.DEBUG
        VapLog.Level.INFO -> Log.INFO
        VapLog.Level.WARN -> Log.WARN
        VapLog.Level.ERROR -> Log.ERROR
    }
    val full = if (error != null) message + '\n' + Log.getStackTraceString(error) else message
    Log.println(priority, tag, full)
}

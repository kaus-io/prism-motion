package com.zxhhyj.vap.decode

import android.content.Context

/**
 * Process-level Android context so asset-backed [com.zxhhyj.vap.player.VapSource.Asset]
 * can open `AssetFileDescriptor`s. The `vap-compose` Android artifact installs it
 * automatically through AndroidX Startup; call [install] manually only when using
 * the decode artifacts without `vap-compose`.
 *
 * 进程级 Android context，供基于 assets 的 [com.zxhhyj.vap.player.VapSource.Asset]
 * 打开 `AssetFileDescriptor`。`vap-compose` 的 Android 产物会通过 AndroidX Startup
 * 自动安装；只有绕开 `vap-compose` 直接使用 decode 产物时才需要手动调用 [install]。
 */
public object VapAndroid {
    @Volatile
    public var applicationContext: Context? = null
        private set

    public fun install(context: Context) {
        applicationContext = context.applicationContext
    }

    public fun requireContext(): Context =
        applicationContext
            ?: error("VapAndroid.install(context) before opening VapSource.Asset")
}

package com.zxhhyj.vap.compose

import android.content.Context
import androidx.startup.Initializer
import com.zxhhyj.vap.decode.VapAndroid

/**
 * AndroidX Startup entry that installs the process-level context before
 * `Application.onCreate`, so asset-backed sources work without manual setup.
 *
 * AndroidX Startup 入口，在 `Application.onCreate` 之前安装进程级 context，
 * 使基于 assets 的播放源无需手动初始化即可使用。
 */
public class VapComposeInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        VapAndroid.install(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

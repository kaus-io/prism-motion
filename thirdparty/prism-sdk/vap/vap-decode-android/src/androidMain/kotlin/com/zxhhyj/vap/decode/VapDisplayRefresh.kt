package com.zxhhyj.vap.decode

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import java.util.concurrent.atomic.AtomicReference

/**
 * Default-display refresh rate. Uses [VapAndroid] application context — no
 * Activity, no Choreographer. `0` means unknown; the present grid then keeps
 * a raw `1e9/fps` cell.
 *
 * 默认屏刷新率。走 [VapAndroid] 的 application context，不需要 Activity /
 * Choreographer。`0` 表示未知，呈现格子退回原始 `1e9/fps`。
 */
internal object VapDisplayRefresh {
    private val cachedHz = AtomicReference(0f)

    fun hz(): Float {
        val hit = cachedHz.get()
        if (hit > 0f) return hit
        val hz = query()
        if (hz > 1f) cachedHz.set(hz)
        return hz
    }

    private fun query(): Float {
        return try {
            val ctx = VapAndroid.applicationContext ?: return 0f
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return 0f
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0f
            val hz = if (Build.VERSION.SDK_INT >= 23) {
                display.mode.refreshRate
            } else {
                @Suppress("DEPRECATION")
                display.refreshRate
            }
            if (hz > 1f) hz else 0f
        } catch (_: Throwable) {
            0f
        }
    }
}

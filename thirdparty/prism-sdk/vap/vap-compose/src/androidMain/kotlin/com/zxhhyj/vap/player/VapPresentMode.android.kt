package com.zxhhyj.vap.player

/**
 * Android `actual`: explicit [override] wins, otherwise the Surface host is the default.
 *
 * Android `actual`：显式 [override] 优先，否则默认走 Surface 宿主。
 */
internal actual fun currentVapPresentMode(override: VapPresentMode?): VapPresentMode =
    override ?: VapPresentMode.Surface

package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable

/**
 * JVM `actual`: an explicit [override] (or the [VapPresentMode.Canvas] default) is the
 * only option — desktop has no platform Surface host.
 *
 * JVM `actual`：只能使用显式 [override]，否则取 [VapPresentMode.Canvas] 默认值 ——
 * 桌面没有平台 Surface 宿主。
 */
internal actual fun currentVapPresentMode(override: VapPresentMode?): VapPresentMode =
    override ?: VapPresentMode.Canvas

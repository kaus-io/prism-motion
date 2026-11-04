package com.zxhhyj.vap.player

import androidx.compose.runtime.Composable

/**
 * Android: records [android.content.Context] so [com.zxhhyj.vap.player.VapSource.Asset]
 * can open asset FDs. JVM: no-op.
 *
 * Android：记下 [android.content.Context]，供 [com.zxhhyj.vap.player.VapSource.Asset]
 * 打开 asset FD。JVM：空操作。
 */
@Composable
internal expect fun installVapPlatformContext()

package com.zxhhyj.vap.player

/**
 * Platform poster held by [VapComposition]. Android stores the decoded frame-0
 * bitmap; JVM is a no-op. Lifetime follows the composition — dropping the
 * composition drops the poster.
 *
 * [VapComposition] 持有的平台海报。Android 存放解码后的第 0 帧位图；JVM 为空操作。
 * 生命周期跟随 composition —— composition 被丢弃则海报一并释放。
 */
internal expect class VapPlatformPoster() {
    val isReady: Boolean
    fun warm(source: VapSource, config: VapConfig)
}

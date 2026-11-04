package com.zxhhyj.vap.player

internal actual class VapPlatformPoster actual constructor() {
    actual val isReady: Boolean = false
    actual fun warm(source: VapSource, config: VapConfig) = Unit
}

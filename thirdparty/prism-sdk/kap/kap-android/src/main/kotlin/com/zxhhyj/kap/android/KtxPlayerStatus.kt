package com.zxhhyj.kap.android

/** Player lifecycle, modeled on Coil's painter state. / 播放器生命周期，对齐 Coil painter。 */
public sealed interface KtxPlayerStatus {
    public data object Idle : KtxPlayerStatus

    public data object Preparing : KtxPlayerStatus

    public data object Ready : KtxPlayerStatus

    public data class Failed(public val error: Throwable) : KtxPlayerStatus
}

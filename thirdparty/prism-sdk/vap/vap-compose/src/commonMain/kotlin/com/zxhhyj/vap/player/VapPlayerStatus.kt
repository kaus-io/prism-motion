package com.zxhhyj.vap.player

/**
 * Playback phase for [VapAnimationState], analogous to Coil's `AsyncImagePainter.State`.
 *
 * [VapAnimationState] 的播放阶段，对标 Coil 的 `AsyncImagePainter.State`。
 *
 * Keep [VapAnimation] mounted across these values. Replacing the player until [Ready]
 * tears down the Surface and undoes eager frame 0. Use [Idle]/[Failed] only for optional
 * overlay slots.
 *
 * 请始终挂着 [VapAnimation]。等到 [Ready] 才换上播放器会拆掉 Surface，把第 0 帧 eager
 * present 抵消掉。可选 slot 只叠加在 [Idle] / [Failed] 上。
 */
public sealed interface VapPlayerStatus {
    /** No composition yet. / 还没有 composition。 */
    public data object Idle : VapPlayerStatus

    /** Composition loaded; first frame not on the host. / 已有 composition，首帧还没上屏。 */
    public data object Preparing : VapPlayerStatus

    /** Opening frame or a decoded frame is on the host. / 第 0 帧或解码帧已在宿主上。 */
    public data object Ready : VapPlayerStatus

    /** Decode/present failed. / 解码或呈现失败。 */
    public data class Failed(val error: Throwable) : VapPlayerStatus
}

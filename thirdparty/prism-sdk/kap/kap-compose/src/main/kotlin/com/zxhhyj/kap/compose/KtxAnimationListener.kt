package com.zxhhyj.kap.compose

/**
 * PAGView-style playback callbacks.
 *
 * [onUpdate] is invoked only after a frame is actually presented, not on every
 * tick. Callbacks run on the playback worker; touch Compose state at your own
 * risk (the state properties are already snapshot-backed).
 *
 * PAGView 风格回调。[onUpdate] 只在帧真正呈现后触发，不是每个 tick；回调在播放
 * worker 线程执行。
 */
public interface KtxAnimationListener {
    /** Playback session started for the current Surface. / 当前 Surface 的播放会话开始。 */
    public fun onStart() {}

    /** A frame was presented; [progress] is normalized to `[0, 1]`. / 一帧已呈现。 */
    public fun onUpdate(progress: Float) {}

    /** The animation wrapped to the next repetition. / 进入下一轮重复。 */
    public fun onRepeat() {}

    /** All repetitions finished naturally. / 全部重复自然结束。 */
    public fun onEnd() {}

    /** The session was cancelled before its first frame. / 首帧前被取消。 */
    public fun onCancel() {}

    /** Decode / upload / present failed. / 解码、上传或呈现失败。 */
    public fun onError(error: Throwable) {}
}

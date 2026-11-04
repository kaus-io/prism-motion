package com.zxhhyj.prism.motion

/**
 * 可选的成功呈现通知，时间戳为平台单调时钟纳秒值，不是物理屏幕扫描时间。
 * Optional successful-present notification using platform monotonic nanoseconds, not display scanout timing.
 * 回调可能运行在播放工作线程；实现不得阻塞、抛异常或直接修改 UI。
 * Callbacks may run on playback workers; implementations must not block, throw, or directly mutate UI.
 */
public fun interface PrismMotionFrameObserver {
    public fun onFramePresented(timestampNs: Long)
}

/**
 * 支持逐帧诊断的可选能力；没有观察器时不收集时间戳，不改变播放时钟。
 * Optional per-frame diagnostics; no timestamps are collected without an observer and playback timing is unchanged.
 */
public interface PrismMotionFrameObservable {
    public fun setFrameObserver(observer: PrismMotionFrameObserver?)
}

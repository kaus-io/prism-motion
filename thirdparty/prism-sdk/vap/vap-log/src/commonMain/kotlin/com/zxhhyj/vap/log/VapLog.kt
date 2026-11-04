@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.log

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource

/**
 * Process-wide VAP log entry point.
 *
 * - Writes to the platform sink by default (Android: `android.util.Log`; JVM: `println`).
 *   Replace [sink] to plug in the host app's logger (Bugly, file, etc.).
 * - [minLevel] is the lowest emitted level, default [Level.INFO]. Loop / vsync /
 *   pause traces are [Level.DEBUG]; set [minLevel] to [Level.DEBUG] when diagnosing.
 *   Release builds can raise it to [Level.WARN].
 * - Messages are lazy lambdas so they are not evaluated when the level is filtered,
 *   avoiding string work on hot (per-frame) paths.
 * - [shouldLog] rate-limits by key so events such as dropped frames do not flood.
 *
 * VAP 全局日志入口。
 *
 * - 默认输出到平台实现（Android: `android.util.Log`；JVM: `println`），可用 [sink] 整体替换
 *   （例如接入宿主应用的 Bugly / 文件日志）。
 * - [minLevel] 控制最低输出级别，默认 [Level.INFO]。循环 / vsync / 暂停轨迹是 [Level.DEBUG]，
 *   排查时再降到 [Level.DEBUG]；release 包可由宿主调到 [Level.WARN]。
 * - 消息用惰性 lambda 传入，级别不够时不求值，热路径（每帧调用点）无字符串拼接开销。
 * - [shouldLog] 提供按 key 的限频：丢帧这类每帧可能触发的事件不会刷屏。
 */
public object VapLog {

    public enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

    private val minLevelRef = AtomicReference(Level.INFO)
    private val sinkRef = AtomicReference<VapLogSink?>(null)
    private val rateLimitMarks = AtomicReference<Map<String, TimeSource.Monotonic.ValueTimeMark>>(emptyMap())

    public var minLevel: Level
        get() = minLevelRef.load()
        set(value) {
            minLevelRef.store(value)
        }

    /** Custom sink; null uses the platform default. 自定义输出；为 null 时走平台默认实现。 */
    public var sink: VapLogSink?
        get() = sinkRef.load()
        set(value) {
            sinkRef.store(value)
        }

    public inline fun v(tag: String, message: () -> String) {
        if (Level.VERBOSE >= minLevel) emit(Level.VERBOSE, tag, message(), null)
    }

    public inline fun d(tag: String, message: () -> String) {
        if (Level.DEBUG >= minLevel) emit(Level.DEBUG, tag, message(), null)
    }

    public inline fun i(tag: String, message: () -> String) {
        if (Level.INFO >= minLevel) emit(Level.INFO, tag, message(), null)
    }

    public inline fun w(tag: String, error: Throwable? = null, message: () -> String) {
        if (Level.WARN >= minLevel) emit(Level.WARN, tag, message(), error)
    }

    public inline fun e(tag: String, error: Throwable? = null, message: () -> String) {
        if (Level.ERROR >= minLevel) emit(Level.ERROR, tag, message(), error)
    }

    /** Rate-limited debug: one line per [key] within [intervalMs]. 限频 debug：同一 [key] 在 [intervalMs] 内只输出一条。 */
    public inline fun dRateLimited(key: String, intervalMs: Long, tag: String, message: () -> String) {
        if (shouldLog(key, intervalMs)) d(tag, message)
    }

    /** Rate-limited warn: one line per [key] within [intervalMs]. 限频 warn：同一 [key] 在 [intervalMs] 内只输出一条。 */
    public inline fun wRateLimited(key: String, intervalMs: Long, tag: String, error: Throwable? = null, message: () -> String) {
        if (shouldLog(key, intervalMs)) w(tag, error, message)
    }

    /**
     * Rate-limit check: returns false when the same [key] was allowed less than
     * [intervalMs] ago. Keys are call-site constants and bounded, so the map
     * does not grow without limit.
     *
     * 限频判定：同一 [key] 距上次放行不足 [intervalMs] 时返回 false。
     * key 是调用点常量，数量有界，map 不会无限增长。
     */
    public fun shouldLog(key: String, intervalMs: Long): Boolean {
        val now = TimeSource.Monotonic.markNow()
        while (true) {
            val cur = rateLimitMarks.load()
            val last = cur[key]
            if (last != null && last.elapsedNow().inWholeMilliseconds < intervalMs) return false
            if (rateLimitMarks.compareAndSet(cur, cur + (key to now))) return true
        }
    }

    /** Clear all rate-limit marks (e.g. when a new playback session starts). 清空所有限频记录（例如新的播放会话开始时）。 */
    public fun resetRateLimits() {
        rateLimitMarks.store(emptyMap())
    }

    @PublishedApi
    internal fun emit(level: Level, tag: String, message: String, error: Throwable?) {
        val s = sink
        if (s != null) s(level, tag, message, error) else platformLog(level, tag, message, error)
    }
}

/** Log sink: `level / tag / message / error`. 日志输出端：`level / tag / message / error`。 */
public typealias VapLogSink = (level: VapLog.Level, tag: String, message: String, error: Throwable?) -> Unit

internal expect fun platformLog(level: VapLog.Level, tag: String, message: String, error: Throwable?)

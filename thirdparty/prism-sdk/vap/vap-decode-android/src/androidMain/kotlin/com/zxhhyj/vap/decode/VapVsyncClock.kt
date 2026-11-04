package com.zxhhyj.vap.decode

import com.zxhhyj.vap.log.VapLog
import com.zxhhyj.vap.vk.VapVkNative
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide present ticker (PAG AnimationTicker analogue).
 * 全进程共享的 present ticker（与 PAG AnimationTicker 类似）。
 *
 * Wakes are [VapFramePulse] on [VapSharedVkRuntime] — one Handler, no Main /
 * Choreographer hop. The clock does **not** loop at display refresh: a tick
 * runs when a session has a pending image, and the next pulse is armed only
 * if a frame is still waiting on the target-fps grid (typically ~33ms at 30fps
 * instead of every 8–16ms vsync).
 * 唤醒走 [VapSharedVkRuntime] 上的 [VapFramePulse]，只有一条 Handler，
 * 不经过 Main / Choreographer。时钟**不会**按屏刷空转：有待呈现的会话才 tick；
 * 仅当帧还在等目标 fps 格子时才预约下一次（30fps 约 33ms，而不是每个 8–16ms 的 vsync）。
 *
 * No SharedFlow / collect hop on the present path.
 * present 路径上没有 SharedFlow / collect 的额外跳转。
 */
internal object VapVsyncClock {
    private val listenersLock = Any()
    private val vkSessions = ArrayList<VapVulkanSession>()

    private const val IDLE_WATCH_MS = 15_000L

    // Reused per-tick buffers. dispatchTick runs only on the shared vk handler thread, so the
    // snapshot copy needs no lock beyond [listenersLock]; the handle array avoids both a
    // per-tick LongArray allocation and the Long boxing an ArrayList<Long> would incur.
    // 每 tick 复用的缓冲。dispatchTick 只在共享 vk handler 线程上运行，因此快照拷贝
    // 除 [listenersLock] 外无需额外加锁；句柄数组同时避免每 tick 的 LongArray 分配
    // 以及 ArrayList<Long> 带来的 Long 装箱。
    private val tickSnapshot = ArrayList<VapVulkanSession>(8)
    private val batchHandles = LongArray(16)
    private var batchCount = 0
    private val batchSessions = ArrayList<VapVulkanSession>(16)
    private val idleWatchScheduled = AtomicBoolean(false)
    private val idleWatch = Runnable {
        idleWatchScheduled.set(false)
        val sessions = snapshotVk()
        val stuck = sessions.filter { it.isClockIdlePlaying() }
        if (stuck.isEmpty()) return@Runnable
        VapLog.w("VapLoop") {
            "vsync-idle-watch n=${stuck.size}/${sessions.size} armed=${pulse.isArmed()} " +
                sessions.joinToString(" | ") { it.idleDiag() }
        }
        scheduleIdleWatch()
    }

    private val pulse = VapFramePulse(
        handlerProvider = { VapSharedVkRuntime.imageHandler() },
        onPulse = { frameTimeNanos -> dispatchTick(frameTimeNanos) },
    )

    private fun dispatchTick(frameTimeNanos: Long) {
        // Copy into the reused buffer under the lock instead of allocating an array per tick.
        // 在锁内拷贝到复用缓冲，而不是每 tick 分配一个数组。
        val sessions: List<VapVulkanSession>
        synchronized(listenersLock) {
            tickSnapshot.clear()
            tickSnapshot.addAll(vkSessions)
            sessions = tickSnapshot
        }
        // onVsyncTick 必须始终持 tickLock：它会读取/关闭 pendingImage 并访问
        // image.hardwareBuffer，而 discardPendingPresent() 等入口也会拿 tickLock。
        // 之前非批量分支没加锁，一旦 BATCH_PRESENT 关闭就会重现 SIGSEGV。
        // onVsyncTick must always hold tickLock (pendingImage / hardwareBuffer lifetime).
        synchronized(VapVulkanPipeline.tickLock) {
            if (VapVulkanPipeline.BATCH_PRESENT) {
                // Prepare every due session, then a single native submit+present covers all.
                // 准备好所有到期的会话，再通过一次原生 submit+present 覆盖全部。
                // Sweep first: skipping sessions otherwise pin stale batch slots.
                // 先扫描释放：否则被跳过的会话会持续占用陈旧批次槽位。
                for (s in sessions) s.retireSignaled()
                batchCount = 0
                batchSessions.clear()
                for (s in sessions) {
                    if (s.onVsyncTick(frameTimeNanos)) {
                        val h = s.nativeHandle
                        if (h != 0L && batchCount < batchHandles.size) {
                            batchHandles[batchCount++] = h
                            batchSessions.add(s)
                        }
                    }
                }
                if (batchCount > 0) {
                    val submitted = VapVkNative.nativeSubmitBatch(batchHandles, batchCount)
                    for (s in batchSessions) s.finishBatch(submitted)
                }
            } else {
                for (s in sessions) s.onVsyncTick(frameTimeNanos)
            }
        }
        rearmAfterTick(sessions, System.nanoTime())
    }

    fun requestImmediateTick() {
        // armIfIdle: Home attach must not cancel Touch's fps-grid wait with arm(0).
        // That spun the shared clock at 0-delay, burned Touch's pre-arm budget,
        // and left it vsync-idle with no ImageReader wake.
        // armIfIdle：Home 挂 Surface 不能用 arm(0) 拆掉 Touch 的 fps 格子。
        // 否则共享时钟按 0 延时空转，耗光 Touch 的预排额度，变成 vsync-idle
        // 且没有 ImageReader 再唤醒。
        if (!pulse.armIfIdle(0L)) {
            VapLog.wRateLimited("vsync-no-handler", 1_000L, "VapLoop") {
                "requestImmediateTick dropped: vk-handler=null listeners=${snapshotVk().size}"
            }
        }
    }

    fun registerVulkan(session: VapVulkanSession): Boolean {
        synchronized(listenersLock) {
            if (!vkSessions.contains(session)) vkSessions.add(session)
        }
        if (VapSharedVkRuntime.imageHandler() == null) {
            synchronized(listenersLock) {
                vkSessions.remove(session)
                if (isEmptyLocked()) stopAll()
            }
            VapLog.w("VapLoop") { "registerVulkan failed: vk-handler=null" }
            return false
        }
        wake()
        return true
    }

    fun unregisterVulkan(session: VapVulkanSession) {
        val empty = synchronized(listenersLock) {
            vkSessions.remove(session)
            isEmptyLocked()
        }
        if (empty) stopAll()
    }

    /**
     * Arm the next pulse without adding a session. Used when a session is already
     * registered and a new ImageReader frame arrives. Does **not** cancel an
     * already-scheduled fps-grid wait.
     * 不新增会话，只预约下一拍。会话已在列表里、ImageReader 又来了一帧时使用。
     * **不会**拆掉已经预约的 fps 格子等待。
     */
    fun wake() {
        if (!pulse.armIfIdle(0L)) {
            VapLog.wRateLimited("arm-no-handler", 1_000L, "VapLoop") {
                "wake dropped: vk-handler=null"
            }
        }
    }

    private fun isEmptyLocked(): Boolean = vkSessions.isEmpty()

    private fun snapshotVk(): Array<VapVulkanSession> =
        synchronized(listenersLock) { vkSessions.toTypedArray() }

    private fun rearmAfterTick(sessions: List<VapVulkanSession>, frameTimeNanos: Long) {
        var minDelayMs: Long? = null
        for (s in sessions) {
            val delay = s.nextPresentDelayMillis(frameTimeNanos) ?: continue
            minDelayMs = minOf(minDelayMs ?: Long.MAX_VALUE, delay)
        }
        val delay = minDelayMs
        val anyIdlePlaying = sessions.any { it.isClockIdlePlaying() }
        if (delay != null) {
            if (!pulse.arm(delay)) {
                VapLog.wRateLimited("arm-no-handler", 1_000L, "VapLoop") {
                    "armTick delay=${delay}ms vk-handler=null"
                }
            }
        }
        // Sibling sessions keep the shared pulse alive; do not reset this on every
        // Home tick or the watch never fires for an idle Touch overlay.
        // 兄弟会话会让共享脉冲继续转；不能每个 Home tick 都重计时，否则空闲的
        // Touch 浮层永远打不出这条 watch。
        if (anyIdlePlaying || (delay == null && sessions.isNotEmpty())) {
            scheduleIdleWatch()
        } else {
            cancelIdleWatch()
        }
    }

    private fun scheduleIdleWatch() {
        if (!idleWatchScheduled.compareAndSet(false, true)) return
        val handler = VapSharedVkRuntime.imageHandler()
        if (handler == null) {
            idleWatchScheduled.set(false)
            return
        }
        handler.postDelayed(idleWatch, IDLE_WATCH_MS)
    }

    private fun cancelIdleWatch() {
        if (!idleWatchScheduled.getAndSet(false)) return
        VapSharedVkRuntime.imageHandler()?.removeCallbacks(idleWatch)
    }

    private fun stopAll() {
        cancelIdleWatch()
        pulse.cancel()
    }
}

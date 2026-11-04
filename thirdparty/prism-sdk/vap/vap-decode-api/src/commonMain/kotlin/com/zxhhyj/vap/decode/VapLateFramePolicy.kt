package com.zxhhyj.vap.decode

/**
 * What to do when a pending frame's own index is behind the wall-clock cell.
 *
 * 待呈现帧自身序号落后于挂钟格子时怎么处理。
 */
public enum class VapLateFrameAction {
    Present,
    Rebase,
    Drop,
}

/**
 * Late-frame policy for the AHB present grid. A frame that just arrived
 * (`arrivalAgeNs < cell`) is never dropped: [acquireLatestImage] skips
 * buffers but only advances the delivery index by one, so the seq looks
 * stale while the pixels are fresh. Dropping that frame is what froze
 * Touch when Home blocked the shared vk handler.
 *
 * AHB 呈现格子的落后帧策略。刚到的帧（`arrivalAgeNs < cell`）绝不丢：
 * [acquireLatestImage] 会跳过缓冲却只给交付序号 +1，序号看起来过期、
 * 像素其实是新的。Home 堵住共享 vk 线程时把 Touch 冻住，就是丢掉了这种帧。
 */
public object VapLateFramePolicy {
    public fun decide(
        behind: Long,
        dropStreak: Int,
        arrivalAgeNs: Long,
        cellNs: Long,
        dropToleranceCells: Long = 1L,
        maxCatchupCells: Long = 15L,
        maxConsecutiveDrops: Int = 3,
    ): VapLateFrameAction {
        if (behind <= dropToleranceCells) return VapLateFrameAction.Present
        if (behind > maxCatchupCells) return VapLateFrameAction.Rebase
        if (cellNs > 0L && arrivalAgeNs < cellNs) return VapLateFrameAction.Rebase
        return if (dropStreak < maxConsecutiveDrops) {
            VapLateFrameAction.Drop
        } else {
            VapLateFrameAction.Present
        }
    }
}

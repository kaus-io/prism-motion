@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import com.zxhhyj.vap.vk.VapVkNative
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Constants shared by [VapVulkanSession] and the present ticker.
 *
 * [VapVulkanSession] 与呈现时钟共用的常量。
 */
internal object VapVulkanPipeline {
    const val IMPLEMENTED: Boolean = true

    /** Must stay in sync with native `kFramesInFlight`. / 必须与原生 `kFramesInFlight` 保持同步。 */
    const val kInflightImages: Int = 2

    /**
     * Batch path: each vsync tick prepares every due session, then one native
     * submit+present covers all of them (single vkQueueSubmit / vkQueuePresentKHR).
     * 批处理路径：每个 vsync tick 准备好所有到期的会话，然后一次原生 submit+present
     * 覆盖全部（单次 vkQueueSubmit / vkQueuePresentKHR）。
     */
    const val BATCH_PRESENT: Boolean = true

    /**
     * A pending frame older than the wall-clock cell by more than this many target-fps
     * cells is dropped instead of presented. One cell of tolerance absorbs single-vsync
     * jitter without drop churn.
     * 待呈现帧比挂钟格子旧超过该值（目标 fps 格子数）时丢弃而不呈现。
     * 一个格子的容差可吸收单 vsync 抖动，避免频繁丢帧。
     */
    const val kDropToleranceCells: Long = 1L

    /**
     * Max consecutive stale drops before a frame is presented anyway. Bounds catch-up
     * bursts and keeps the screen updating when decode (not present) is the bottleneck.
     * 连续丢弃过期帧的上限，超过后无论如何都呈现一帧。限制追赶 burst 长度，
     * 并在解码（而非呈现）成为瓶颈时保证画面持续更新。
     */
    const val kMaxConsecutiveDrops: Int = 3

    /**
     * Beyond this many cells of lag the clock is rebased onto the decode position
     * instead of grinding through the backlog frame by frame (long stall / background).
     * 落后超过该格子数时，把时钟重置到解码位置，而不是逐帧碾过积压（长时间停顿 / 后台）。
     */
    const val kMaxCatchupCells: Long = 15L

    /**
     * Predictive pre-arm: after a present the clock stays on the cell grid for up to
     * this many ticks that find no pending frame, so an on-time decode presents exactly
     * on its cell boundary instead of paying a wake-on-arrival hop. After the
     * budget is spent the clock idles and falls back to wake-on-arrival (pause / dry
     * session), so an idle session never spins the pulse.
     * 预测性预排：一次 present 后，时钟在格子网格上最多再空跑这么多次 tick，
     * 使按时到达的解码帧正好踩在格子边界呈现，省掉“帧到才唤醒”的回程。
     * 额度用完后时钟休眠，退回“帧到才唤醒”（暂停 / 无帧会话），
     * 因此空闲会话永远不会空转脉冲。
     */
    const val kMaxPrearmedEmptyTicks: Int = 8

    /**
     * Serializes a whole vsync tick (prepare loop + [VapVkNative.nativeSubmitBatch])
     * against [VapVulkanSession.release], so a batch never touches a destroyed engine.
     * 将整个 vsync tick（prepare 循环 + [VapVkNative.nativeSubmitBatch]）与
     * [VapVulkanSession.release] 串行化，确保批处理不会触达已被销毁的引擎。
     */
    internal val tickLock = Any()
}

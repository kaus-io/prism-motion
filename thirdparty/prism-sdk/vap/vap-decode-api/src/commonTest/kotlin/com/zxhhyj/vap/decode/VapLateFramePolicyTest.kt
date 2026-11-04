package com.zxhhyj.vap.decode

import kotlin.test.Test
import kotlin.test.assertEquals

class VapLateFramePolicyTest {
    private val cell = 33_333_331L

    @Test
    fun onTimeOrOneCellLate_presents() {
        assertEquals(
            VapLateFrameAction.Present,
            VapLateFramePolicy.decide(behind = 0, dropStreak = 0, arrivalAgeNs = 0L, cellNs = cell),
        )
        assertEquals(
            VapLateFrameAction.Present,
            VapLateFramePolicy.decide(behind = 1, dropStreak = 0, arrivalAgeNs = cell * 2, cellNs = cell),
        )
    }

    @Test
    fun farBehind_rebasesInsteadOfGrinding() {
        assertEquals(
            VapLateFrameAction.Rebase,
            VapLateFramePolicy.decide(behind = 16, dropStreak = 0, arrivalAgeNs = cell * 20, cellNs = cell),
        )
    }

    @Test
    fun justArrivedButSeqBehind_rebases_homeBlocksSharedHandler() {
        // acquireLatestImage skipped buffers: seq +1, wall clock +3, age=0.
        // 跳过 ImageReader 缓冲：序号 +1、挂钟 +3、刚到。
        assertEquals(
            VapLateFrameAction.Rebase,
            VapLateFramePolicy.decide(behind = 3, dropStreak = 0, arrivalAgeNs = 0L, cellNs = cell),
        )
    }

    @Test
    fun satInPendingAndLate_dropsUntilCap() {
        assertEquals(
            VapLateFrameAction.Drop,
            VapLateFramePolicy.decide(behind = 3, dropStreak = 0, arrivalAgeNs = cell * 3, cellNs = cell),
        )
        assertEquals(
            VapLateFrameAction.Present,
            VapLateFramePolicy.decide(behind = 3, dropStreak = 3, arrivalAgeNs = cell * 3, cellNs = cell),
        )
    }
}

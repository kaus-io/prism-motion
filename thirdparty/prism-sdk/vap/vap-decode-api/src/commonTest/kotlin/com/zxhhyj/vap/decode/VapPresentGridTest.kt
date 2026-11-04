package com.zxhhyj.vap.decode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VapPresentGridTest {
    @Test
    fun cellPeriod_withoutRefresh_isOneSecondOverFps() {
        assertEquals(1_000_000_000L / 30L, VapPresentGrid.cellPeriodNanos(30, 0f))
        assertEquals(0L, VapPresentGrid.cellPeriodNanos(0, 60f))
    }

    @Test
    fun cellPeriod_quantizesToWholeVsyncsWhenClose() {
        val hz60 = VapPresentGrid.cellPeriodNanos(30, 60f)
        assertEquals(2, kotlin.math.round(hz60 * 60.0 / 1_000_000_000.0).toInt())
        assertTrue(kotlin.math.abs(hz60 - 1_000_000_000L / 30L) * 100 <= (1_000_000_000L / 30L) * 8)

        val hz90 = VapPresentGrid.cellPeriodNanos(30, 90f)
        assertEquals(3, kotlin.math.round(hz90 * 90.0 / 1_000_000_000.0).toInt())

        val hz120 = VapPresentGrid.cellPeriodNanos(30, 120f)
        assertEquals(4, kotlin.math.round(hz120 * 120.0 / 1_000_000_000.0).toInt())
    }

    @Test
    fun cellPeriod_keepsRawWhenVsyncQuantizeWouldSkew() {
        val raw24 = 1_000_000_000L / 24L
        assertEquals(raw24, VapPresentGrid.cellPeriodNanos(24, 60f))
    }

    @Test
    fun logicalIndex_advancesOnCellBoundaries() {
        val cell = 33_333_333L
        assertEquals(0L, VapPresentGrid.logicalIndex(0L, cell))
        assertEquals(0L, VapPresentGrid.logicalIndex(cell - 1L, cell))
        assertEquals(1L, VapPresentGrid.logicalIndex(cell, cell))
        assertEquals(2L, VapPresentGrid.logicalIndex(cell * 2L + 10L, cell))
    }

    @Test
    fun delayMillis_wakesLeadBeforeDue() {
        val due = 100_000_000L
        val lead = VapPresentGrid.WAKE_LEAD_NS
        assertEquals(0L, VapPresentGrid.delayMillisUntilDue(due, due, lead))
        assertEquals(0L, VapPresentGrid.delayMillisUntilDue(due, due - lead, lead))
        assertEquals(10L, VapPresentGrid.delayMillisUntilDue(due, due - lead - 10_000_000L, lead))
        assertEquals(0L, VapPresentGrid.delayMillisUntilDue(due, due + 5_000_000L, lead))
    }

    @Test
    fun isDue_allowsLeadWindow() {
        val due = 50_000_000L
        val lead = VapPresentGrid.WAKE_LEAD_NS
        assertEquals(false, VapPresentGrid.isDue(nowNs = due - lead - 1L, dueNs = due, leadNs = lead))
        assertEquals(true, VapPresentGrid.isDue(nowNs = due - lead, dueNs = due, leadNs = lead))
        assertEquals(true, VapPresentGrid.isDue(nowNs = due + 1L, dueNs = due, leadNs = lead))
    }
}

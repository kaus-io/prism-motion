package com.zxhhyj.kap.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KtxPlaybackDefaultsTest {
    @Test fun manifestCanDisableExperimentsEvenForDebugBuilds() {
        assertFalse(ktxExperimentOverridesAllowed(2, manifestEnabled = false))
        assertTrue(ktxExperimentOverridesAllowed(2, manifestEnabled = true))
        assertFalse(ktxExperimentOverridesAllowed(0, manifestEnabled = true))
        assertFalse(ktxExperimentOverridesAllowed(0, manifestEnabled = false))
    }
    @Test fun notificationThrottlingIsEnabledWithoutOtherExperiments() {
        val defaults = KtxDebugOptions()
        assertTrue(defaults.throttleState)
        assertNull(defaults.ringSize)
        assertFalse(defaults.initialFileUpload)
        assertFalse(defaults.residentFrames)
        assertFalse(defaults.narrowTeardown)
        assertFalse(defaults.fastCacheHit)
        assertFalse(defaults.sharedVsync)
        assertFalse(defaults.earlyCache)
        assertFalse(defaults.measure)
        assertFalse(defaults.legacyFrameUpload)
        assertFalse(defaults.filterTest)
        assertFalse(defaults.filterAuto)
        assertFalse(defaults.nativeHue)
        assertFalse(defaults.holdFrameZero)
        assertFalse(defaults.forcePressed)
        assertFalse(defaults.filterNormal)
        org.junit.Assert.assertEquals("embedded", defaults.surfaceMode)
    }

    @Test fun unthrottledModeRemainsAvailableForDebugComparisons() {
        assertFalse(KtxDebugOptions(throttleState = false).throttleState)
    }
}

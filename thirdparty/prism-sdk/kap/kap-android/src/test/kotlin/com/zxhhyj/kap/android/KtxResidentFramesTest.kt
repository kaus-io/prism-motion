package com.zxhhyj.kap.android

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class KtxResidentFramesTest {
    @Test fun initialFramesAndRepeatedFramesDoNotUpload() {
        val ring = KtxResidentFrames(4)
        repeat(4) { frame ->
            assertTrue(ring.present(frame, { true }, { error("already resident") }))
        }
        assertTrue(ring.present(4, { error("not resident") }, { true }))
        assertTrue(ring.present(4, { true }, { error("already resident") }))
        assertTrue(ring.present(0, { error("overwritten") }, { true }))
    }

    @Test fun failedUploadEvictsOldAndNewIdentities() {
        val ring = KtxResidentFrames(1)
        assertFalse(ring.present(1, { error("not resident") }, { false }))
        assertFalse(ring.present(1, { error("failed upload") }, { false }))
        assertTrue(ring.present(0, { error("old data may have changed") }, { true }))
    }

    @Test fun readFailureInvalidatesSlot() {
        val ring = KtxResidentFrames(1)
        try {
            ring.present(1, { error("not resident") }, { throw java.io.IOException() })
            error("expected failure")
        } catch (_: java.io.IOException) {
            assertTrue(ring.present(0, { error("invalid slot") }, { true }))
        }
    }
}

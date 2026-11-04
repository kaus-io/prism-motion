package com.zxhhyj.kap.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class KtxHueRowsTest {
    @Test fun zeroRotationIsIdentity() {
        assertArrayEquals(floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f),
            KtxHueRows.rotate(0f), 0.00001f)
    }

    @Test fun hueRotationPreservesNeutralColorsAndPadding() {
        for (angle in listOf(-180f, -150f, 0f, 45f, 180f)) {
            val rows = KtxHueRows.rotate(angle)
            repeat(3) { row ->
                val offset = row * 4
                assertEquals(1f, rows[offset] + rows[offset + 1] + rows[offset + 2], 0.00001f)
                assertEquals(0f, rows[offset + 3], 0f)
            }
        }
    }
}

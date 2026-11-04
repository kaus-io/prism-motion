package com.zxhhyj.kap.android

import kotlin.math.cos
import kotlin.math.sin

/** Row-major RGB hue transform, padded to three vec4 push-constant rows. */
internal object KtxHueRows {
    fun rotate(degrees: Float): FloatArray {
        val angle = Math.toRadians(degrees.toDouble())
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        val r = 0.213f
        val g = 0.715f
        val b = 0.072f
        return floatArrayOf(
            r + c * (1f - r) - s * r, g - c * g - s * g, b - c * b + s * (1f - b), 0f,
            r - c * r + s * 0.143f, g + c * (1f - g) + s * 0.140f, b - c * b - s * 0.283f, 0f,
            r - c * r - s * (1f - r), g - c * g + s * g, b + c * (1f - b) + s * b, 0f,
        )
    }
}

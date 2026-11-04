package com.zxhhyj.kap.compose

internal enum class KtxSurfaceAttachResult { Stale, Failed, Attached }

/** Native attach can outlive its Surface; check ownership on both sides of the call. */
internal inline fun attachKtxSurfaceIfCurrent(
    isCurrent: () -> Boolean,
    attach: () -> Boolean,
): KtxSurfaceAttachResult {
    if (!isCurrent()) return KtxSurfaceAttachResult.Stale
    val attached = attach()
    return when {
        !isCurrent() -> KtxSurfaceAttachResult.Stale
        attached -> KtxSurfaceAttachResult.Attached
        else -> KtxSurfaceAttachResult.Failed
    }
}

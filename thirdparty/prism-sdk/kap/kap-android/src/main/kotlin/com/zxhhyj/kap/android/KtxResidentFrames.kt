package com.zxhhyj.kap.android

/** Player-owned; output detach does not evict the texture. */
internal class KtxResidentFrames(size: Int) {
    @PublishedApi internal val frames = IntArray(size) { it }

    inline fun present(frame: Int, resident: (Int) -> Boolean, upload: (Int) -> Boolean): Boolean {
        val slot = frame % frames.size
        if (frames[slot] == frame) return resident(slot)
        // Upload may have been submitted even if present failed. Both identities
        // must be considered invalid until the replacement succeeds.
        frames[slot] = -1
        return upload(slot).also { if (it) frames[slot] = frame }
    }
}

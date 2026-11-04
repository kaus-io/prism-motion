package com.zxhhyj.kap.compose

/** A Surface lifetime and one geometry of its output, not an animation timeline position. */
internal data class KtxSurfaceOutput(
    val epoch: Long,
    val revision: Long,
    val initialTimestamp: Long,
)

/** Pure state; its owner serializes calls. A queue submission alone is not display content. */
internal class KtxDisplayContentGate {
    var output: KtxSurfaceOutput? = null
        private set
    private var submitted = false
    private var consumed = false

    val hasDisplayContent: Boolean get() = output != null && submitted && consumed
    val needsConsumption: Boolean get() = output != null && !consumed

    fun reset(output: KtxSurfaceOutput) {
        this.output = output
        submitted = false
        consumed = false
    }

    fun invalidate() {
        output = null
        submitted = false
        consumed = false
    }

    fun onSubmitted(output: KtxSurfaceOutput) {
        if (this.output == output) submitted = true
    }

    /** Called only after a TextureView update has passed a hardware UI frame commit. */
    fun onConsumed(output: KtxSurfaceOutput, timestamp: Long) {
        if (this.output == output && timestamp != output.initialTimestamp) consumed = true
    }
}

package com.zxhhyj.vap.decode

import com.zxhhyj.vap.vk.VapVkNative

/**
 * Best-effort process-wide Vulkan warmup. Call when a VAP composition is loaded so the
 * shared VkDevice (instance/device/shader modules, ~60ms on CC4PRO) is ready before the
 * first surface frame. The first engine consumes the prewarm reference; if playback never
 * starts, the device remains resident for the process lifetime (a few MB).
 *
 * 尽力而为的进程级 Vulkan 预热。在加载 VAP composition 时调用，使共享 VkDevice
 * （instance/device/shader module，CC4PRO 约 60ms）在首个 Surface 帧前就绪。
 * 首个引擎会消费预热引用；若从未播放，设备会常驻到进程结束（几 MB）。
 */
public object VapGpuPrewarm {
    private val requested = java.util.concurrent.atomic.AtomicBoolean(false)

    public fun prewarm() {
        if (!requested.compareAndSet(false, true)) return
        VapSharedVkRuntime.launchMaintenance {
            VapVkNative.nativePrewarmSharedDevice()
        }
    }
}

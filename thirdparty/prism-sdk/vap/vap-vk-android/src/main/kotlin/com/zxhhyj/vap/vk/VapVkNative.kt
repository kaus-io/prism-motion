package com.zxhhyj.vap.vk

import android.hardware.HardwareBuffer
import android.view.Surface

/**
 * JNI bridge to the Vulkan 1.1 AHB composite/present engine
 * (`MediaCodec → AHardwareBuffer → VkImage → swapchain`).
 *
 * Vulkan 1.1 AHB 合成与呈现引擎的 JNI 桥接层
 *（`MediaCodec → AHardwareBuffer → VkImage → swapchain`）。
 *
 * Lives in a classic [com.android.library] module because AGP 9's
 * `com.android.kotlin.multiplatform.library` does not support NDK/CMake.
 *
 * 由于 AGP 9 的 `com.android.kotlin.multiplatform.library` 不支持 NDK/CMake，
 * 本桥接层位于传统 [com.android.library] 模块中。
 */
public object VapVkNative {
    init {
        System.loadLibrary("vap_vk")
    }

    /**
     * Creates an engine from the packed-video dimensions and alpha/RGB pixel rectangles.
     * Returns an opaque native handle, or 0 on failure; each nonzero handle must be passed to [nativeDestroy].
     *
     * 根据打包视频尺寸及 alpha/RGB 像素矩形创建引擎。
     * 返回不透明原生句柄，失败时返回 0；每个非零句柄最终都必须传给 [nativeDestroy]。
     */
    public external fun nativeCreate(
        videoW: Int,
        videoH: Int,
        alphaX: Int,
        alphaY: Int,
        alphaW: Int,
        alphaH: Int,
        rgbX: Int,
        rgbY: Int,
        rgbW: Int,
        rgbH: Int,
    ): Long

    /**
     * Destroys a native engine; 0 is a no-op and a destroyed handle must not be reused.
     *
     * 销毁原生引擎；传入 0 时不执行操作，已销毁句柄不得再次使用。
     */
    public external fun nativeDestroy(handle: Long)

    /**
     * Sets or replaces the output [Surface] and requested extent.
     * Native code retains its own window reference and does not take ownership of the Java [Surface].
     *
     * 设置或替换输出 [Surface] 及请求尺寸。
     * 原生层持有独立的窗口引用，不接管 Java [Surface] 的所有权。
     */
    public external fun nativeSetOutputSurface(
        handle: Long,
        surface: Surface,
        width: Int,
        height: Int,
    ): Boolean

    /**
     * Detaches and releases the engine's native output-window reference.
     * The caller remains responsible for the Java [Surface].
     *
     * 解除并释放引擎持有的原生输出窗口引用。
     * Java [Surface] 仍由调用方负责管理。
     */
    public external fun nativeClearOutputSurface(handle: Long)

    /**
     * Uploads packed frame-0 ARGB pixels as the engine's RGBA opening texture. The pixels stay in
     * retriever orientation; the shared UVs align the sampled image with the codec frame. Returns
     * false when the texture cannot be created; the caller may retry with new pixels.
     *
     * 把打包第 0 帧 ARGB 像素上传为引擎的 RGBA opening 纹理。像素保持 retriever 方向；共享 UV
     * 会把采样结果对齐到 codec 帧。纹理创建失败时返回 false；调用方可用新像素重试。
     */
    public external fun nativeSetOpeningPixels(
        handle: Long,
        pixels: IntArray,
        width: Int,
        height: Int,
    ): Boolean

    /**
     * Presents the uploaded RGBA opening texture to the current output swapchain.
     * Returns false when no opening texture or no output swapchain is available.
     *
     * 把已上传的 RGBA opening 纹理 present 到当前输出交换链。没有 opening 纹理或输出
     * 交换链不可用时返回 false。
     */
    public external fun nativePresentOpening(handle: Long): Int

    /**
     * Imports, composites, submits, and presents one [HardwareBuffer].
     * [bufferId] is retained in the signature for a possible future import cache; the current
     * native engine does not read it, so callers may pass 0. The caller owns the Java wrapper;
     * native code does not close it and retains the references required for in-flight GPU work.
     *
     * 导入、合成、提交并呈现一个 [HardwareBuffer]。
     * [bufferId] 仅为将来可能的导入缓存保留在签名中；当前原生引擎不读取它，调用方可以传 0。
     * Java 包装对象归调用方所有；原生层不会将其关闭，并会持有 GPU 在途工作所需的引用。
     */
    public external fun nativePresentHardwareBuffer(
        handle: Long,
        hardwareBuffer: HardwareBuffer,
        bufferId: Long,
    ): Boolean

    /**
     * Present helper. The caller retains ownership of [hardwareBuffer].
     *
     * No stable buffer id is computed: the native engine does not consume it, and reflecting
     * `HardwareBuffer.getId()` on every present was pure overhead (plus one boxed `Long`).
     *
     * 呈现辅助方法。[hardwareBuffer] 的所有权仍归调用方。
     *
     * 不再计算稳定缓冲区 ID：原生引擎不消费该值，每帧反射 `HardwareBuffer.getId()`
     * 纯属开销（且每帧产生一次 `Long` 装箱）。
     */
    public fun presentHardwareBuffer(handle: Long, hardwareBuffer: HardwareBuffer): Boolean {
        return nativePresentHardwareBuffer(handle, hardwareBuffer, 0L)
    }

    /**
     * Batch path: imports the AHB and records commands without queue submission.
     * A following [nativeSubmitBatch] submits and presents all selected prepared engines together.
     * The [HardwareBuffer] ownership and [bufferId] rules match [nativePresentHardwareBuffer].
     *
     * 批处理路径：导入 AHB 并记录命令，但不提交队列。
     * 随后的 [nativeSubmitBatch] 会一起提交并呈现所有选中的已准备引擎。
     * [HardwareBuffer] 所有权及 [bufferId] 规则与 [nativePresentHardwareBuffer] 相同。
     */
    public external fun nativePreparePresent(
        handle: Long,
        hardwareBuffer: HardwareBuffer,
        bufferId: Long,
    ): Int

    public enum class PrepareResult { Failed, Prepared, NotReady, Recreate }

    /**
     * Prepare helper; the caller retains buffer ownership. No stable buffer id is computed for
     * the same reason as [presentHardwareBuffer].
     *
     * 准备辅助方法；缓冲区所有权仍归调用方。不计算稳定缓冲区 ID，原因同 [presentHardwareBuffer]。
     */
    public fun preparePresent(handle: Long, hardwareBuffer: HardwareBuffer): PrepareResult {
        return when (nativePreparePresent(handle, hardwareBuffer, 0L)) {
            1 -> PrepareResult.Prepared
            2 -> PrepareResult.NotReady
            3 -> PrepareResult.Recreate
            else -> PrepareResult.Failed
        }
    }

    /**
     * Creates the process-wide shared VkDevice ahead of the first engine. The first
     * engine consumes the prewarm reference; if no engine starts, the device stays
     * resident for the process lifetime.
     * 提前创建进程级共享 VkDevice。首个引擎会消费预热引用；若没有引擎启动，
     * 设备会常驻到进程结束。
     */
    public external fun nativePrewarmSharedDevice()

    /**
     * Uploads a KTX2 ASTC/ETC texture array. One array layer is one animation frame.
     * The output surface must already be bound. Returns false on unsupported format,
     * malformed KTX2, or Vulkan errors.
     * 上传 KTX2 ASTC/ETC 纹理数组，每个 layer 对应一帧。输出 Surface 必须先绑定。
     * 格式不支持、KTX2 损坏或 Vulkan 失败时返回 false。
     */
    public external fun nativeSetTextureArray(handle: Long, data: ByteArray): Boolean

    /** Presents one layer of the uploaded texture array. / 呈现已上传纹理数组的指定 layer。 */
    public external fun nativePresentSequenceFrame(handle: Long, layer: Int): Boolean

    /**
     * Streams one raw ASTC layer into the sequence array and presents it. The byte
     * count must equal the per-layer size of the uploaded KTX2 array.
     * 流式上传单层原始 ASTC 到纹理数组并呈现；字节数必须等于已上传 KTX2 数组的单层大小。
     */
    public external fun nativePresentSequenceFrameData(
        handle: Long,
        layer: Int,
        data: ByteArray,
    ): Boolean

    public external fun nativeResetImportPool(handle: Long)

    /**
     * Releases the packed frame-0 opening texture/pipeline after the first codec frame.
     * The resources are recreated if [nativeSetOpeningPixels] is called again.
     * 首帧 codec 呈现后释放打包第 0 帧 opening 纹理/管线；再次调用
     * [nativeSetOpeningPixels] 时会按需重建。
     */
    public external fun nativeReleaseOpeningResources(handle: Long)

    public external fun nativeCancelPrepared(handle: Long)

    /**
     * Uses one `vkQueueSubmit` and one `vkQueuePresentKHR` for prepared work from the first
     * [count] nonzero entries of [handles]. [handles] is a caller-owned buffer that may be
     * longer than [count]; only the first [count] entries are read, so it can be reused across
     * ticks without reallocating.
     *
     * 对 [handles] 前 [count] 个非零条目对应的已准备工作使用一次 `vkQueueSubmit` 和一次
     * `vkQueuePresentKHR`。[handles] 是调用方所有、可长于 [count] 的缓冲；只读取前 [count]
     * 项，因此可跨 tick 复用而无需重新分配。
     */
    public external fun nativeSubmitBatch(handles: LongArray, count: Int): Boolean

    /**
     * Retires submitted slots whose batch GPU work has signaled, releasing their retained resources.
     *
     * 回收批次 GPU 工作已发出完成信号的提交槽，并释放其持有的资源。
     */
    public external fun nativeRetireSignaled(handle: Long)

    /**
     * Returns true only when the handle has both an initialized Vulkan device and swapchain.
     *
     * 仅当句柄已初始化 Vulkan 设备和交换链时返回 true。
     */
    public external fun nativeReady(handle: Long): Boolean

    /**
     * Runs a device smoke test for C `AHardwareBuffer` Vulkan import and the
     * ImageReader/MediaCodec AHB identity probe (candidate B).
     * [videoPath] is an MP4 on app-readable storage used for decode round-trip tests.
     * Returns a multi-line report and also logs under tag `VapAhbProbe`.
     *
     * 执行 C `AHardwareBuffer` Vulkan 导入设备冒烟测试，以及
     * ImageReader/MediaCodec AHB 身份探测（候选 B）。
     * [videoPath] 是应用可读存储中的 MP4，用于解码往返测试。
     * 返回多行报告，并同时使用 `VapAhbProbe` 标签写入日志。
     */
    public external fun nativeProbeAhb(videoPath: String): String

    /**
     * B0 diagnostic: pins [buffer]'s native wrapper and returns its pointer, or 0 on failure.
     * The caller owns the returned native reference and must release it exactly once through
     * [nativeProbeAhbRelease]; the Java [HardwareBuffer] remains caller-owned.
     *
     * B0 诊断：固定 [buffer] 的原生包装并返回其指针，失败时返回 0。
     * 调用方拥有返回的原生引用，且必须通过 [nativeProbeAhbRelease] 恰好释放一次；
     * Java [HardwareBuffer] 的所有权仍归调用方。
     */
    public external fun nativeProbeAhbAcquire(buffer: HardwareBuffer): Long

    /**
     * Releases a native reference returned by [nativeProbeAhbAcquire]; 0 is a no-op.
     *
     * 释放 [nativeProbeAhbAcquire] 返回的原生引用；传入 0 时不执行操作。
     */
    public external fun nativeProbeAhbRelease(ptr: Long)
}

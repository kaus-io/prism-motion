#pragma once

#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <cstddef>
#include <cstdint>

namespace vap_vk {

    /**
     * Normalized texture-coordinate rectangle within the packed video frame.
     * 打包视频帧中的归一化纹理坐标矩形。
     */
    struct RectUv {
        float x0 = 0.f;
        float y0 = 0.f;
        float x1 = 1.f;
        float y1 = 1.f;
    };

    /**
     * Immutable packed-frame dimensions and alpha/RGB sampling regions.
     * 不可变的打包帧尺寸及 Alpha/RGB 采样区域。
     */
    struct CreateParams {
        int videoW = 1;
        int videoH = 1;
        RectUv alphaUv{};
        RectUv rgbUv{};
        bool hasAlpha = true;
    };

    /**
     * Vulkan compositor for reconstructing and presenting VAP frames from AHBs.
     * 用于从 AHB 重建并呈现 VAP 帧的 Vulkan 合成器。
     */
    enum class PrepareResult : int { Failed = 0, Prepared = 1, NotReady = 2, Recreate = 3 };

    class Engine {
    public:
        /**
         * Create an engine; returns nullptr if shared Vulkan resources cannot initialize.
         * 创建引擎；共享 Vulkan 资源初始化失败时返回 nullptr。
         */
        static Engine *create(const CreateParams &params);

        /**
         * Release all resources and delete this engine; the pointer is invalid afterward.
         * 释放全部资源并删除当前引擎；调用后该指针失效。
         */
        void destroy();

        /**
         * Bind an output window; the engine retains its own ANativeWindow reference.
         * 绑定输出窗口；引擎会持有独立的 ANativeWindow 引用。
         */
        bool setOutputWindow(ANativeWindow *window, int width, int height);

        /**
         * Wait for outstanding device work, then release the surface and window.
         * 等待未完成的设备工作后释放 Surface 与窗口。
         */
        void clearOutputWindow();

        /**
         * Upload packed frame-0 pixels as an RGBA opening texture. Each uint32_t stores
         * R in bits 0-7, G in bits 8-15, B in bits 16-23, and A in bits 24-31, matching
         * VK_FORMAT_R8G8B8A8_UNORM on little-endian Android.
         * 将打包第 0 帧像素上传为 RGBA opening 纹理。每个 uint32_t 的 0-7/8-15/16-23/24-31
         * 位分别存 R/G/B/A，对应 little-endian Android 上的 VK_FORMAT_R8G8B8A8_UNORM。
         */
        bool setOpeningPixels(const uint32_t *pixels, int width, int height);

        /**
         * Present the uploaded opening texture to the current swapchain. Does not touch
         * the AHB import cache or YCbCr sampler state.
         * 把已上传的 opening 纹理 present 到当前交换链。不触碰 AHB 导入缓存或 YCbCr 采样器状态。
         */
        PrepareResult presentOpening();

        /**
         * Import an AHB, reconstruct VAP alpha, and present with fence synchronization.
         * 导入 AHB、重建 VAP Alpha，并通过栅栏同步完成呈现。
         * @param bufferId HardwareBuffer.getId() on API 31+, or 0 when unavailable.
         *                 API 31+ 传 HardwareBuffer.getId()，不可用时传 0。
         */
        bool presentHardwareBuffer(AHardwareBuffer *buffer, uint64_t bufferId);

        /**
         * Prepare without blocking on maintenance, fences or swapchain availability.
         * NotReady retains caller ownership; retry on a later tick.
         * 准备时不等待维护任务、栅栏或交换链图像；NotReady 时保留帧稍后重试。
         */
        PrepareResult preparePresentHardwareBuffer(AHardwareBuffer *buffer, uint64_t bufferId);

        /**
         * Submit all prepared engines once and present all participating swapchains once.
         * 通过一次提交呈现全部已准备引擎所参与的交换链。
         */
        static bool submitPreparedBatch(Engine **engines, int count);

        /**
         * Discard a prepared frame that has not reached the queue.
         * 丢弃尚未进入队列的已准备帧。
         */
        void cancelPreparedPresent();

        /**
         * Retire frame slots whose shared batch fence is already signaled.
         * 回收共享批次栅栏已触发的帧槽位。
         */
        void retireSignaledBatches();

        /**
         * Uploads a KTX2 ASTC/ETC texture array. One array layer is one animation
         * frame. The output surface must already be bound so the render pass exists.
         * Returns false on unsupported format, malformed KTX2, or Vulkan errors.
         *
         * 上传 KTX2 ASTC/ETC 纹理数组，每个 layer 对应一帧。调用前必须先绑定输出
         * Surface（render pass 已创建）。格式不支持、KTX2 损坏或 Vulkan 失败时返回 false。
         */
        bool setTextureArray(const uint8_t *data, size_t length);

        /**
         * Presents one layer of the uploaded texture array.
         * 呈现已上传纹理数组的指定 layer。
         */
        bool presentSequenceFrame(uint32_t layer);

        /**
         * Streams one raw ASTC layer into the sequence array and presents it. The data
         * length must equal the per-layer byte size of the uploaded KTX2 array.
         * 流式上传单层原始 ASTC 到纹理数组并呈现；data 长度必须等于已上传数组的单层字节数。
         */
        bool presentSequenceFrameData(uint32_t layer, const uint8_t *data, size_t length);

        void resetImportPool();

        /**
         * Destroy the packed frame-0 opening texture and its pipeline after the first
         * codec frame has been presented. The resources are recreated on demand if
         * setOpeningPixels() is called again later.
         * 首个 codec 帧呈现后销毁打包第 0 帧 opening 纹理及其管线；若之后再次调用
         * setOpeningPixels()，资源会按需重建。
         */
        void releaseOpeningResources();

        /**
         * Return whether both the Vulkan device and output swapchain are usable.
         * 返回 Vulkan 设备与输出交换链是否均可用。
         */
        [[nodiscard]] bool ready() const;

        Engine(const Engine &) = delete;

        Engine &operator=(const Engine &) = delete;

    private:
        Engine() = default;

        ~Engine() = default;

        struct Impl;
        Impl *impl_ = nullptr;

        /**
         * Keep the implementation reachable for analyzers that inspect only this header.
         * 让仅检查此头文件的分析器仍可追踪实现对象。
         */
        [[nodiscard]] Impl *pimpl() noexcept { return impl_; }

        [[nodiscard]] const Impl *pimpl() const noexcept { return impl_; }
    };

} // namespace vap_vk

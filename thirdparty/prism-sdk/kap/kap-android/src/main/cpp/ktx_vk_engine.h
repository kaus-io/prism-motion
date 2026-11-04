#pragma once

#include <android/native_window.h>

#include <cstddef>
#include <cstdint>

namespace kap_vk {

    /**
     * Vulkan player for one KTX2 texture-array animation.
     *
     * One array layer is one animation frame. Static playback uploads every layer;
     * streaming playback keeps a small ring and uploads one layer per present.
     * Calls on the same engine must be serialized by its owner (KtxSurfacePlayer).
     * Native synchronization protects the shared VkQueue, not instance ownership.
     *
     * 单个 KTX2 纹理数组动画的 Vulkan 播放器。每个 array layer 是一帧；
     * 静态播放一次上传全部 layer，流式播放只保留环形数组并逐帧上传。
     */
    class Engine {
    public:
        /** Create an engine; returns nullptr if Vulkan cannot initialize. / 创建引擎。 */
        static Engine *create();

        /** Release all resources and delete the engine. / 释放资源并销毁引擎。 */
        void destroy();

        /** Bind an output Surface; the engine retains its own ANativeWindow reference. / 绑定输出 Surface。 */
        bool setOutputWindow(ANativeWindow *window, int width, int height);

        /** Wait for outstanding work and release the Surface / swapchain. / 释放输出 Surface 与交换链。 */
        void clearOutputWindow();

        /**
         * Upload a KTX2 file. The output Surface should already be bound so the
         * render pass exists; otherwise the pipeline is created on the first present.
         * 上传 KTX2；建议先绑定输出 Surface，否则首次 present 时再创建管线。
         */
        bool setTextureArray(const uint8_t *data, size_t length);

        /**
         * Upload already-decoded level data (e.g. after KTX2 Zstd decompression).
         * [format] is a raw Vulkan format value; [data] length must equal
         * width x height x layers in that format.
         *
         * 上传已解码的 level 数据（例如 KTX2 Zstd 解压后）；[format] 为 Vulkan 格式值，
         * [data] 长度必须等于该格式下 width x height x layers 的字节数。
         */
        bool setTextureArrayRaw(
                uint32_t format,
                uint32_t width,
                uint32_t height,
                uint32_t layers,
                const uint8_t *data,
            size_t length);

        bool setTextureArrayFile(uint32_t format, uint32_t width, uint32_t height,
                                 uint32_t layers, int fd, int64_t offset, size_t length);
        void setNarrowTeardown(bool enabled);
        bool setHueRows(const float *rows, size_t length);

        /**
         * Decompress a KTX2 Zstd level and upload it as a texture array.
         * 解压 KTX2 Zstd level 并作为纹理数组上传。
         */
        bool setTextureArrayZstd(
                uint32_t format,
                uint32_t width,
                uint32_t height,
                uint32_t layers,
                const uint8_t *compressed,
                size_t compressedLength,
                size_t uncompressedLength);

        /** Present one layer of the uploaded array. / 呈现已上传数组的一层。 */
        bool presentFrame(uint32_t layer);
        bool presentFrameBytes(uint32_t layer, const void *bytes, size_t length);

        /**
         * Read one raw layer directly into staging and present it. The owner keeps
         * fd open for the whole call. ioError distinguishes failed reads from a
         * temporarily unavailable output; no partial frame is ever submitted.
         */
        bool presentFrameFile(uint32_t layer, int fd, int64_t offset, size_t length, int *ioError);

        /** True when device, swapchain and texture array are usable. / 设备、交换链与纹理数组是否可用。 */
        [[nodiscard]] bool ready() const;

        Engine(const Engine &) = delete;

        Engine &operator=(const Engine &) = delete;

    private:
        Engine() = default;

        ~Engine() = default;

        struct Impl;

        Impl *impl_ = nullptr;

        [[nodiscard]] Impl *pimpl() noexcept { return impl_; }

        [[nodiscard]] const Impl *pimpl() const noexcept { return impl_; }
    };

} // namespace kap_vk

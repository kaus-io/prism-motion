#include "vap_vk_engine.h"
#include "vap_vk_shared_device.h"
#include "vap_retired_import_pool.h"

#include "shaders/vap_frag_spv.h"
#include "shaders/vap_seq_frag_spv.h"
#include "shaders/vap_vert_spv.h"

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <cstddef>
#include <cstring>
#include <mutex>
#include <unordered_map>
#include <vector>

#define VAP_VK_LOG_TAG "VapVk"
#define VAP_LOGI(...) __android_log_print(ANDROID_LOG_INFO, VAP_VK_LOG_TAG, __VA_ARGS__)
#define VAP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, VAP_VK_LOG_TAG, __VA_ARGS__)

namespace vap_vk {
    namespace {

        // Two in-flight slots; keep this value aligned with VapVulkanPipeline.kInflightImages.
        // 两个在途槽位；此值需与 VapVulkanPipeline.kInflightImages 保持一致。
        constexpr int kFramesInFlight = 2;

        // Retired AHB import pool. Each entry pins a full video AHB (≈1.3MB for 720x1280
        // YUV420); a 32-entry cap could keep ~26MB resident. On CC4PRO (Adreno 643) a
        // one-entry pool held 24fps and 1% low within noise while saving ~27MB PSS, so
        // prefer a small pool here. Raise this only if a target device shows import-bound
        // CPU (the old 12-entry trace thrashed at ~50% hit rate).
        // AHB 导入池。每个条目都钉住一整块视频 AHB（720x1280 YUV420 约 1.3MB）；
        // 32 条上限可能常驻约 26MB。CC4PRO（Adreno 643）实测 1 条池仍保持 24fps，
        // 1% low 在噪声范围内，同时 PSS 降低约 27MB，因此这里优先小池。
        // 仅当目标设备出现导入瓶颈 CPU 时再调大（旧 12 条跟踪命中率约 50%）。
        constexpr size_t kMaxPooledImports = 1;

        bool check(VkResult r, const char *what) {
            if (r == VK_SUCCESS) return true;
            VAP_LOGE("%s failed: %d", what, (int) r);
            return false;
        }

        uint32_t findMemoryType(VkPhysicalDevice physical, uint32_t typeBits,
                                VkMemoryPropertyFlags required) {
            VkPhysicalDeviceMemoryProperties memProps{};
            vkGetPhysicalDeviceMemoryProperties(physical, &memProps);
            for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
                if ((typeBits & (1u << i)) &&
                    (memProps.memoryTypes[i].propertyFlags & required) == required) {
                    return i;
                }
            }
            return UINT32_MAX;
        }

        // Interleaved vertex layout: clip position.xy | alpha UV.xy | RGB UV.xy.
        // 交错顶点布局：裁剪空间位置.xy | Alpha UV.xy | RGB UV.xy。
        constexpr uint32_t kVertexFloats = 6;
        constexpr uint32_t kVertexStride = kVertexFloats * sizeof(float);
        constexpr uint32_t kVertexOffsetPos = 0;
        constexpr uint32_t kVertexOffsetAlpha = 2 * sizeof(float);
        constexpr uint32_t kVertexOffsetRgb = 4 * sizeof(float);

        struct AhbImport {
            AHardwareBuffer *ahb = nullptr; // Held while GPU sampling can use it.
                                            // GPU 采样期间始终持有。
            VkImage image = VK_NULL_HANDLE;
            VkDeviceMemory memory = VK_NULL_HANDLE;
            VkImageView view = VK_NULL_HANDLE;
            uint64_t generation = 0;
            uint64_t bufferId = 0; // AHardwareBuffer_getId; 0 = unknown.
                                   // AHardwareBuffer_getId；0 表示未知。
        };

        struct FrameSlot {
            VkCommandBuffer cmd = VK_NULL_HANDLE;
            VkFence fence = VK_NULL_HANDLE;
            VkSemaphore imageAvailable = VK_NULL_HANDLE;
            VkSemaphore renderFinished = VK_NULL_HANDLE;
            VkDescriptorSet descSet = VK_NULL_HANDLE;
            // View/AHB currently sampled by this slot's descriptor set (non-owning aliases).
            // 此槽位描述符集当前采样的 view/AHB（非拥有别名）。
            VkImageView boundView = VK_NULL_HANDLE;
            AHardwareBuffer *boundAhb = nullptr;
            // Slot-owned import; destroyed after the GPU fence that sampled it signals.
            // Without the old AHB cache this is the only owner of image/view/memory.
            // 槽位自有的导入；采样它的 GPU 栅栏触发后销毁。
            // 去掉旧 AHB 缓存后，这是 image/view/memory 的唯一持有者。
            AhbImport import{};
            bool submitted = false;
            // Batch-path state: batch owns completion after submission; prepared means recorded
            // and awaiting submitPreparedBatch.
            // 批次路径状态：提交后由 batch 管理完成事件；prepared 表示已录制并等待
            // submitPreparedBatch。
            SharedDevice::BatchSlot *batch = nullptr;
            bool prepared = false;
            uint32_t preparedImageIndex = 0;
            // True after prepareAhb acquire signals imageAvailable and before submit waits it.
            // prepareAhb 已对 imageAvailable 发信号、提交尚未等待该信号时为 true。
            bool acquireSignaled = false;

            // Host-visible staging for streaming one ASTC layer into the sequence
            // texture array. Owned by this frame slot and reused across presents.
            // 流式上传单层 ASTC 到纹理数组用的 host-visible staging；
            // 由当前帧槽位独占并跨帧复用。
            VkBuffer uploadBuffer = VK_NULL_HANDLE;
            VkDeviceMemory uploadMemory = VK_NULL_HANDLE;
            void *uploadMapped = nullptr;
            size_t uploadCapacity = 0;
        };

        // SharedDevice and QueueGuard are defined in vap_vk_shared_device.h.
        // SharedDevice 与 QueueGuard 定义于 vap_vk_shared_device.h。

    } // namespace

    struct Engine::Impl {
        CreateParams params{};
        SharedDevice *shared = nullptr;

        // Non-owning aliases into SharedDevice; never destroy them from an engine session.
        // 指向 SharedDevice 的非拥有别名；引擎会话不得销毁这些对象。
        VkInstance instance = VK_NULL_HANDLE;
        VkPhysicalDevice physical = VK_NULL_HANDLE;
        VkDevice device = VK_NULL_HANDLE;
        uint32_t graphicsQueueFamily = 0;
        VkQueue graphicsQueue = VK_NULL_HANDLE;
        VkShaderModule vertModule = VK_NULL_HANDLE;
        VkShaderModule fragModule = VK_NULL_HANDLE;

        VkCommandPool cmdPool = VK_NULL_HANDLE;
        FrameSlot frames[kFramesInFlight]{};
        uint32_t frameIndex = 0;
        bool frameDescSetsReady = false;
        // Batch-path acquire fence: a CPU wait replaces a queue-side acquire semaphore.
        // 批次路径的获取栅栏：以 CPU 等待替代队列侧的获取信号量。
        VkFence acquireFence = VK_NULL_HANDLE;

        VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
        VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
        VkRenderPass renderPass = VK_NULL_HANDLE;
        VkPipeline pipeline = VK_NULL_HANDLE;
        VkDescriptorPool descPool = VK_NULL_HANDLE;

        VkBuffer vertexBuffer = VK_NULL_HANDLE;
        VkDeviceMemory vertexMemory = VK_NULL_HANDLE;

        // Opening frame path: ordinary RGBA sampled image, deliberately separate from the
        // AHB/YCbCr pipeline so the first codec PRIVATE buffer still defines the conversion.
        // 首帧路径：普通 RGBA 采样图像，刻意与 AHB/YCbCr 管线分离，使首个 codec PRIVATE
        // 缓冲仍能决定 YCbCr 转换。
        VkImage openingImage = VK_NULL_HANDLE;
        VkDeviceMemory openingMemory = VK_NULL_HANDLE;
        VkImageView openingView = VK_NULL_HANDLE;
        VkSampler openingSampler = VK_NULL_HANDLE;
        VkDescriptorSetLayout openingSetLayout = VK_NULL_HANDLE;
        VkPipelineLayout openingPipelineLayout = VK_NULL_HANDLE;
        VkPipeline openingPipeline = VK_NULL_HANDLE;
        VkDescriptorSet openingDescSet = VK_NULL_HANDLE;
        int openingWidth = 0;
        int openingHeight = 0;
        VkBuffer openingStaging = VK_NULL_HANDLE;
        VkDeviceMemory openingStagingMemory = VK_NULL_HANDLE;
        VkCommandBuffer openingUploadCmd = VK_NULL_HANDLE;
        VkFence openingUploadFence = VK_NULL_HANDLE;

        // Texture-array playback path: one array layer per animation frame.
        // 纹理数组播放路径：一个 array layer 对应一帧。
        VkShaderModule seqFragModule = VK_NULL_HANDLE;
        VkImage seqImage = VK_NULL_HANDLE;
        VkDeviceMemory seqMemory = VK_NULL_HANDLE;
        VkImageView seqView = VK_NULL_HANDLE;
        VkSampler seqSampler = VK_NULL_HANDLE;
        VkDescriptorSetLayout seqSetLayout = VK_NULL_HANDLE;
        VkDescriptorPool seqDescPool = VK_NULL_HANDLE;
        VkDescriptorSet seqDescSet = VK_NULL_HANDLE;
        VkPipelineLayout seqPipelineLayout = VK_NULL_HANDLE;
        VkPipeline seqPipeline = VK_NULL_HANDLE;
        uint32_t seqLayers = 0;
        uint32_t seqWidth = 0;
        uint32_t seqHeight = 0;
        uint32_t seqLayerBytes = 0;

        // Vulkan YCbCr conversion must be baked into the descriptor layout as an immutable sampler.
        // Vulkan YCbCr 转换必须以不可变采样器形式固化在描述符布局中。
        VkSamplerYcbcrConversion ycbcrConv = VK_NULL_HANDLE;
        VkSampler ycbcrSampler = VK_NULL_HANDLE;
        uint64_t cachedExternalFormat = UINT64_MAX;
        VkFormat cachedAhbFormat = VK_FORMAT_MAX_ENUM;

        // ImageReader buffers in one stream share stable AHB properties. Resolve them once with
        // AHardwareBuffer_describe plus the Vulkan driver query, then reuse the cached copies.
        // 同一 ImageReader 流中的 AHB 属性稳定一致；通过 AHardwareBuffer_describe 与
        // Vulkan 驱动查询解析一次，随后复用缓存副本。
        bool ahbPropsCached = false;
        AHardwareBuffer_Desc cachedAhbDesc{};
        VkAndroidHardwareBufferFormatPropertiesANDROID cachedFmtProps{};
        VkAndroidHardwareBufferPropertiesANDROID cachedAhbProps{};

        // Image create params (format/extent/usage) and the stream's allocation size are stable
        // within one ImageReader session, so the dedicated-memory requirement and the memory
        // type index are identical for every import. Resolve them once, then skip the per-frame
        // AHardwareBuffer_describe / vkGetImageMemoryRequirements2 / memory-type scan.
        // 同一 ImageReader 会话内图像创建参数（格式/尺寸/用途）与分配大小稳定，因此专用内存
        // 要求与内存类型索引对每次导入都相同。解析一次后即可跳过每帧的 AHardwareBuffer_describe /
        // vkGetImageMemoryRequirements2 / 内存类型扫描。
        bool ahbImportReqsCached = false;
        bool cachedAhbNeedsDedicated = false;
        uint32_t cachedAhbMemoryTypeIndex = 0;

        // AHB import pool. The old pointer-keyed cache never hit because it did not hold a
        // reference (addresses were recycled). The held-refs identity probe proves the
        // `AHardwareBuffer_fromHardwareBuffer` pointer is stable for the same underlying
        // buffer while a reference is held, and that holding refs does not stall the
        // BufferQueue — so key the pool on that pointer and keep the AHB ref while pooled.
        // `HardwareBuffer.getId()` is a stub returning 0 on the target devices, so pointer
        // identity is the only usable key.
        // AHB 导入池。旧的指针键缓存不命中是因为它不持引用（地址被回收）。held-refs 身份探针
        // 证明：持有引用时，同一底层缓冲区的 `AHardwareBuffer_fromHardwareBuffer` 指针稳定，
        // 且持有引用不会阻塞 BufferQueue —— 因此以该指针为键，并在池中保留 AHB 引用。
        // 目标设备 `HardwareBuffer.getId()` 恒返回 0，指针身份是唯一可用的键。
        RetiredImportPool<AhbImport, kMaxPooledImports> importPool;

        // Log dedicated-allocation requirements only for the first AHB import.
        // 仅为首次 AHB 导入记录专用分配要求。
        bool ahbDedicatedLogged = false;

        ANativeWindow *window = nullptr;
        VkSurfaceKHR surface = VK_NULL_HANDLE;
        VkSwapchainKHR swapchain = VK_NULL_HANDLE;
        bool swapchainInvalid = false;
        VkFormat swapFormat = VK_FORMAT_R8G8B8A8_UNORM;
        VkExtent2D swapExtent{1, 1};
        std::vector<VkImage> swapImages;
        std::vector<VkImageView> swapViews;
        std::vector<VkFramebuffer> framebuffers;

        bool bindShared();

        bool createCommandResources();

        bool createShadersAndPipelineBase();

        bool createVertexBuffer();

        bool createSwapchain(int width, int height);

        void destroySwapchain();

        bool ensureRenderPass(VkFormat format);

        bool ensureGraphicsPipeline();

        bool createPipelineForLayout(VkPipelineLayout layout, VkPipeline &outPipeline,
                                     VkShaderModule fragOverride = VK_NULL_HANDLE);

        bool createOpeningResources(const uint32_t *pixels, int width, int height);

        bool ensureOpeningPipeline();

        void destroyOpeningResources();

        void recordOpeningCommands(FrameSlot &slot, uint32_t imageIndex);

        bool presentOpening();

        bool ensureYcbcrResources(const VkAndroidHardwareBufferFormatPropertiesANDROID &fmtProps,
                                  bool externalFormat);

        void destroyYcbcrResources();

        bool allocateFrameDescSets();

        void freeFrameDescSets();

        void destroyAhbImport(AhbImport &imp) const;

        // Import-pool helpers (see importPool). takePooledImport hands ownership of a pooled
        // import to a slot; recycleAhbImport returns a retired slot import to the pool.
        // 导入池辅助方法（见 importPool）。takePooledImport 把池中导入的所有权交给槽位；
        // recycleAhbImport 把已回收槽位的导入还回池中。
        bool takePooledImport(uintptr_t key, AhbImport &out);
        void recycleAhbImport(AhbImport &imp);
        void destroyPooledImports();

        bool acquireAhbImport(AHardwareBuffer *buffer,
                              uint64_t bufferId,
                              const VkAndroidHardwareBufferFormatPropertiesANDROID &fmtProps,
                              const VkAndroidHardwareBufferPropertiesANDROID &ahbProps,
                              bool externalFormat,
                              AhbImport &out);

        // Bind the freshly imported AHB to this slot's descriptor set.
        // 把刚导入的 AHB 绑定到本槽位的描述符集。
        void bindSlotImport(FrameSlot &slot, const AhbImport &imp);

        bool drainAcquireSemaphores();

        bool waitRetireFrame(FrameSlot &slot);

        void waitRetireAllFrames();

        bool resolveAhbProps(AHardwareBuffer *buffer,
                             VkAndroidHardwareBufferFormatPropertiesANDROID &fmtProps,
                             VkAndroidHardwareBufferPropertiesANDROID &ahbProps);

        void recordDrawCommands(FrameSlot &slot, VkImage srcImage, VkImageView srcView,
                                uint32_t imageIndex);

        void recordClearCommands(FrameSlot &slot, uint32_t imageIndex);

        bool setTextureArray(const uint8_t *data, size_t length);
        bool ensureSequencePipeline();
        void recordSequenceCommands(FrameSlot &slot, uint32_t imageIndex, uint32_t layer);
        bool presentSequence(uint32_t layer);
        bool presentSequenceData(uint32_t layer, const uint8_t *data, size_t length);
        bool ensureUploadBuffer(FrameSlot &slot, size_t size);
        void recordSequenceUploadCommands(FrameSlot &slot, uint32_t imageIndex,
                                         uint32_t layer);
        void destroySequenceResources();

        // PAG-style clearAll: present a transparent swapchain frame as soon as the
        // window exists so TextureView does not show the default opaque black buffer.
        // PAG 的 clearAll：窗口一就绪就 present 一帧透明，避免 TextureView 露出默认不透明黑底。
        bool presentClear();

        bool presentAhb(AHardwareBuffer *buffer, uint64_t bufferId);

        PrepareResult prepareAhb(AHardwareBuffer *buffer, uint64_t bufferId);

        bool retireOpeningUpload(bool wait);

        void cancelPrepared();

        // Retire slots whose shared batch fence is signaled. Every session runs this each tick,
        // including skipped presenters, so holds tracks only genuinely in-flight participants.
        // 回收共享批次栅栏已触发的槽位。每个会话每次 tick 都执行，包括跳过呈现者，
        // 因而 holds 只统计真实在途参与者。
        void retireSignaledBatches();

        [[nodiscard]] bool ready() const;

        void destroy();
    };


    bool Engine::Impl::bindShared() {
        shared = SharedDevice::acquire();
        if (!shared) return false;
        // The first engine consumes the process prewarm reference. Other engines
        // keep their own acquire reference.
        // 首个引擎消费进程预热引用；其它引擎保留自己的引用。
        SharedDevice::consumePrewarm();
        instance = shared->instance;
        physical = shared->physical;
        device = shared->device;
        graphicsQueueFamily = shared->graphicsQueueFamily;
        graphicsQueue = shared->graphicsQueue;
        vertModule = shared->vertModule;
        fragModule = shared->fragModule;
        return true;
    }

    bool Engine::Impl::createCommandResources() {
        VkCommandPoolCreateInfo pci{
                .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                .pNext = nullptr,
                .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
                .queueFamilyIndex = graphicsQueueFamily,
        };
        if (!check(vkCreateCommandPool(device, &pci, nullptr, &cmdPool), "vkCreateCommandPool")) {
            return false;
        }
        VkCommandBuffer cmds[kFramesInFlight];
        VkCommandBufferAllocateInfo cai{
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                .pNext = nullptr,
                .commandPool = cmdPool,
                .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                .commandBufferCount = kFramesInFlight,
        };
        if (!check(vkAllocateCommandBuffers(device, &cai, cmds), "vkAllocateCommandBuffers")) {
            return false;
        }
        VkFenceCreateInfo fci{
                .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
                .pNext = nullptr,
                .flags = VK_FENCE_CREATE_SIGNALED_BIT,
        };
        VkSemaphoreCreateInfo sci{
                .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
        };
        for (int i = 0; i < kFramesInFlight; i++) {
            frames[i].cmd = cmds[i];
            if (!check(vkCreateFence(device, &fci, nullptr, &frames[i].fence), "vkCreateFence") ||
                !check(vkCreateSemaphore(device, &sci, nullptr, &frames[i].imageAvailable),
                       "imageAvailable") ||
                !check(vkCreateSemaphore(device, &sci, nullptr, &frames[i].renderFinished),
                       "renderFinished")) {
                return false;
            }
        }
        if (!check(vkCreateFence(device, &fci, nullptr, &acquireFence), "acquireFence")) {
            return false;
        }
        return true;
    }

    bool Engine::Impl::createShadersAndPipelineBase() {
        // Shader modules live on SharedDevice. Push descriptors need no pool; otherwise each
        // session owns one. The descriptor-set and pipeline layouts are deferred until the
        // first AHB reveals the immutable YCbCr sampler format.
        // Shader 模块位于 SharedDevice。推送描述符无需池，否则每个会话独占一个池；
        // 描述符集与管线布局延迟到首个 AHB 确定不可变 YCbCr 采样器格式后创建。
        if (shared->pushDescriptors) return true;
        // One extra set covers the RGBA opening texture on non-push-descriptor devices.
        // 额外的 1 个描述符集用于非 push-descriptor 设备上的 RGBA opening 纹理。
        VkDescriptorPoolSize poolSize{
                .type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                .descriptorCount = (uint32_t) (kFramesInFlight + 1),
        };
        VkDescriptorPoolCreateInfo dpci{
                .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
                .pNext = nullptr,
                .flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT,
                .maxSets = (uint32_t) (kFramesInFlight + 1),
                .poolSizeCount = 1,
                .pPoolSizes = &poolSize,
        };
        return check(vkCreateDescriptorPool(device, &dpci, nullptr, &descPool), "descPool");
    }

    void Engine::Impl::destroyAhbImport(AhbImport &imp) const {
        if (imp.view) vkDestroyImageView(device, imp.view, nullptr);
        if (imp.image) vkDestroyImage(device, imp.image, nullptr);
        if (imp.memory) vkFreeMemory(device, imp.memory, nullptr);
        if (imp.ahb) AHardwareBuffer_release(imp.ahb);
        imp = {};
    }

    bool Engine::Impl::takePooledImport(uintptr_t key, AhbImport &out) {
        return importPool.take(key, out);
    }

    void Engine::Impl::recycleAhbImport(AhbImport &imp) {
        if (!imp.ahb || !imp.image) {
            destroyAhbImport(imp);
            return;
        }
        importPool.recycle(reinterpret_cast<uintptr_t>(imp.ahb), imp,
                           [this](AhbImport &value) { destroyAhbImport(value); });
    }

    void Engine::Impl::destroyPooledImports() {
        importPool.clear([this](AhbImport &value) { destroyAhbImport(value); });
    }

    void Engine::Impl::freeFrameDescSets() {
        if (!frameDescSetsReady || !descPool) {
            for (auto &frame: frames) {
                frame.descSet = VK_NULL_HANDLE;
                frame.boundView = VK_NULL_HANDLE;
                frame.boundAhb = nullptr;
            }
            frameDescSetsReady = false;
            return;
        }
        VkDescriptorSet sets[kFramesInFlight];
        int n = 0;
        for (auto &frame: frames) {
            if (frame.descSet) sets[n++] = frame.descSet;
            frame.descSet = VK_NULL_HANDLE;
            frame.boundView = VK_NULL_HANDLE;
            frame.boundAhb = nullptr;
        }
        if (n > 0) vkFreeDescriptorSets(device, descPool, (uint32_t) n, sets);
        frameDescSetsReady = false;
    }

    bool Engine::Impl::allocateFrameDescSets() {
        if (frameDescSetsReady) return true;
        if (shared->pushDescriptors) {
            // No set allocation is needed because the view is pushed during command recording.
            // 视图会在命令录制时推送，因此无需分配描述符集。
            frameDescSetsReady = true;
            return true;
        }
        if (!setLayout || !descPool) return false;
        VkDescriptorSetLayout layouts[kFramesInFlight];
        for (auto &layout: layouts) layout = setLayout;
        VkDescriptorSetAllocateInfo dsai{
                .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
                .pNext = nullptr,
                .descriptorPool = descPool,
                .descriptorSetCount = kFramesInFlight,
                .pSetLayouts = layouts,
        };
        VkDescriptorSet sets[kFramesInFlight];
        if (!check(vkAllocateDescriptorSets(device, &dsai, sets), "frameDescSets")) return false;
        for (int i = 0; i < kFramesInFlight; i++) {
            frames[i].descSet = sets[i];
        }
        frameDescSetsReady = true;
        return true;
    }

    bool Engine::Impl::waitRetireFrame(FrameSlot &slot) {
        if (slot.batch) {
            // Batch submissions complete on the shared batch fence, not this frame's fence.
            // 批次提交由共享批次栅栏完成，而非当前帧自身的栅栏。
            if (!check(vkWaitForFences(device, 1, &slot.batch->fence, VK_TRUE, UINT64_MAX),
                       "waitBatchFence")) {
                return false;
            }
            slot.batch->holds--;
            slot.batch = nullptr;
            slot.submitted = false;
            recycleAhbImport(slot.import);
            slot.boundView = VK_NULL_HANDLE;
            slot.boundAhb = nullptr;
            return true;
        }
        if (!slot.submitted) return true;
        if (!check(vkWaitForFences(device, 1, &slot.fence, VK_TRUE, UINT64_MAX),
                   "waitFrameFence")) {
            return false;
        }
        slot.submitted = false;
        recycleAhbImport(slot.import);
        slot.boundView = VK_NULL_HANDLE;
        slot.boundAhb = nullptr;
        return true;
    }

    void Engine::Impl::waitRetireAllFrames() {
        for (auto &frame: frames) {
            waitRetireFrame(frame);
        }
    }

    void Engine::Impl::destroyYcbcrResources() {
        cancelPrepared();
        waitRetireAllFrames();
        freeFrameDescSets();
        // Pooled imports hold image views created against the ycbcr conversion/sampler torn
        // down below, so they must be released before those resources go away.
        // 池中导入持有的 image view 是针对下方将销毁的 ycbcr 转换/采样器创建的，
        // 因此必须先于这些资源释放。
        destroyPooledImports();
        if (pipeline) {
            vkDestroyPipeline(device, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        if (pipelineLayout) {
            vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
            pipelineLayout = VK_NULL_HANDLE;
        }
        if (setLayout) {
            vkDestroyDescriptorSetLayout(device, setLayout, nullptr);
            setLayout = VK_NULL_HANDLE;
        }
        if (ycbcrSampler) {
            vkDestroySampler(device, ycbcrSampler, nullptr);
            ycbcrSampler = VK_NULL_HANDLE;
        }
        if (ycbcrConv && shared && shared->fnDestroyYcbcr) {
            shared->fnDestroyYcbcr(device, ycbcrConv, nullptr);
            ycbcrConv = VK_NULL_HANDLE;
        }
        cachedExternalFormat = UINT64_MAX;
        cachedAhbFormat = VK_FORMAT_MAX_ENUM;
    }


    bool Engine::Impl::createOpeningResources(const uint32_t *pixels, int width, int height) {
        if (!device || !pixels || width <= 0 || height <= 0) return false;
        cancelPrepared();
        waitRetireAllFrames();
        destroyOpeningResources();

        const VkDeviceSize byteSize = (VkDeviceSize) width * (VkDeviceSize) height * 4u;
        VkBuffer staging = VK_NULL_HANDLE;
        VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
        auto destroyStaging = [&]() {
            if (staging) vkDestroyBuffer(device, staging, nullptr);
            if (stagingMemory) vkFreeMemory(device, stagingMemory, nullptr);
            staging = VK_NULL_HANDLE;
            stagingMemory = VK_NULL_HANDLE;
        };

        VkBufferCreateInfo bci{
                .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .size = byteSize,
                .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
                .queueFamilyIndexCount = 0,
                .pQueueFamilyIndices = nullptr,
        };
        if (!check(vkCreateBuffer(device, &bci, nullptr, &staging), "openingStaging")) return false;
        VkMemoryRequirements stagingReqs{};
        vkGetBufferMemoryRequirements(device, staging, &stagingReqs);
        uint32_t stagingType = findMemoryType(physical, stagingReqs.memoryTypeBits,
                                              VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                              VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (stagingType == UINT32_MAX) {
            VAP_LOGE("openingStagingMemory: no host-visible type");
            destroyStaging();
            return false;
        }
        VkMemoryAllocateInfo stagingAi{
                .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                .pNext = nullptr,
                .allocationSize = stagingReqs.size,
                .memoryTypeIndex = stagingType,
        };
        if (!check(vkAllocateMemory(device, &stagingAi, nullptr, &stagingMemory),
                   "openingStagingMemory") ||
            !check(vkBindBufferMemory(device, staging, stagingMemory, 0), "bindOpeningStaging")) {
            destroyStaging();
            return false;
        }
        void *mapped = nullptr;
        if (!check(vkMapMemory(device, stagingMemory, 0, byteSize, 0, &mapped), "mapOpeningStaging")) {
            destroyStaging();
            return false;
        }
        std::memcpy(mapped, pixels, (size_t) byteSize);
        vkUnmapMemory(device, stagingMemory);

        VkImageCreateInfo ici{
                .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .imageType = VK_IMAGE_TYPE_2D,
                .format = VK_FORMAT_R8G8B8A8_UNORM,
                .extent = {(uint32_t) width, (uint32_t) height, 1},
                .mipLevels = 1,
                .arrayLayers = 1,
                .samples = VK_SAMPLE_COUNT_1_BIT,
                .tiling = VK_IMAGE_TILING_OPTIMAL,
                .usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
                .queueFamilyIndexCount = 0,
                .pQueueFamilyIndices = nullptr,
                .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        };
        if (!check(vkCreateImage(device, &ici, nullptr, &openingImage), "openingImage")) {
            destroyStaging();
            return false;
        }
        VkMemoryRequirements imageReqs{};
        vkGetImageMemoryRequirements(device, openingImage, &imageReqs);
        uint32_t imageType = findMemoryType(physical, imageReqs.memoryTypeBits,
                                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (imageType == UINT32_MAX) {
            VAP_LOGE("openingMemory: no device-local type");
            destroyStaging();
            destroyOpeningResources();
            return false;
        }
        VkMemoryAllocateInfo imageAi{
                .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                .pNext = nullptr,
                .allocationSize = imageReqs.size,
                .memoryTypeIndex = imageType,
        };
        if (!check(vkAllocateMemory(device, &imageAi, nullptr, &openingMemory), "openingMemory") ||
            !check(vkBindImageMemory(device, openingImage, openingMemory, 0), "bindOpeningImage")) {
            destroyStaging();
            destroyOpeningResources();
            return false;
        }

        VkImageViewCreateInfo vci{
                .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .image = openingImage,
                .viewType = VK_IMAGE_VIEW_TYPE_2D,
                .format = ici.format,
                .components = {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
                               VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY},
                .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
        };
        if (!check(vkCreateImageView(device, &vci, nullptr, &openingView), "openingView")) {
            destroyStaging();
            destroyOpeningResources();
            return false;
        }

        VkSamplerCreateInfo sci{
                .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .magFilter = VK_FILTER_LINEAR,
                .minFilter = VK_FILTER_LINEAR,
                .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
                .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                .mipLodBias = 0.f,
                .anisotropyEnable = VK_FALSE,
                .maxAnisotropy = 1.f,
                .compareEnable = VK_FALSE,
                .compareOp = VK_COMPARE_OP_ALWAYS,
                .minLod = 0.f,
                .maxLod = 0.f,
                .borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK,
                .unnormalizedCoordinates = VK_FALSE,
        };
        if (!check(vkCreateSampler(device, &sci, nullptr, &openingSampler), "openingSampler")) {
            destroyStaging();
            destroyOpeningResources();
            return false;
        }

        VkDescriptorSetLayoutBinding binding{
                .binding = 0,
                .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                .descriptorCount = 1,
                .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
                .pImmutableSamplers = &openingSampler,
        };
        VkDescriptorSetLayoutCreateInfo dsl{
                .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
                .pNext = nullptr,
                .flags = shared->pushDescriptors
                         ? VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR
                         : 0u,
                .bindingCount = 1,
                .pBindings = &binding,
        };
        if (!check(vkCreateDescriptorSetLayout(device, &dsl, nullptr, &openingSetLayout),
                   "openingSetLayout")) {
            destroyStaging();
            destroyOpeningResources();
            return false;
        }
        VkPipelineLayoutCreateInfo pli{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .setLayoutCount = 1,
                .pSetLayouts = &openingSetLayout,
                .pushConstantRangeCount = 0,
                .pPushConstantRanges = nullptr,
        };
        if (!check(vkCreatePipelineLayout(device, &pli, nullptr, &openingPipelineLayout),
                   "openingPipelineLayout")) {
            destroyStaging();
            destroyOpeningResources();
            return false;
        }

        if (!shared->pushDescriptors) {
            if (!descPool) {
                destroyStaging();
                destroyOpeningResources();
                return false;
            }
            VkDescriptorSetAllocateInfo dsai{
                    .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
                    .pNext = nullptr,
                    .descriptorPool = descPool,
                    .descriptorSetCount = 1,
                    .pSetLayouts = &openingSetLayout,
            };
            if (!check(vkAllocateDescriptorSets(device, &dsai, &openingDescSet),
                       "openingDescSet")) {
                destroyStaging();
                destroyOpeningResources();
                return false;
            }
            VkDescriptorImageInfo dii{
                    .sampler = openingSampler,
                    .imageView = openingView,
                    .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            };
            VkWriteDescriptorSet write{
                    .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                    .pNext = nullptr,
                    .dstSet = openingDescSet,
                    .dstBinding = 0,
                    .dstArrayElement = 0,
                    .descriptorCount = 1,
                    .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                    .pImageInfo = &dii,
                    .pBufferInfo = nullptr,
                    .pTexelBufferView = nullptr,
            };
            vkUpdateDescriptorSets(device, 1, &write, 0, nullptr);
        }

        VkCommandBuffer cmd = VK_NULL_HANDLE;
        VkCommandBufferAllocateInfo cai{
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                .pNext = nullptr,
                .commandPool = cmdPool,
                .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                .commandBufferCount = 1,
        };
        if (!check(vkAllocateCommandBuffers(device, &cai, &cmd), "openingUploadCmd")) {
            destroyStaging();
            destroyOpeningResources();
            return false;
        }
        VkCommandBufferBeginInfo begin{
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                .pNext = nullptr,
                .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
                .pInheritanceInfo = nullptr,
        };
        vkBeginCommandBuffer(cmd, &begin);
        VkImageMemoryBarrier toTransfer{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = 0,
                .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = openingImage,
                .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
        };
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                             &toTransfer);
        VkBufferImageCopy copy{
                .bufferOffset = 0,
                .bufferRowLength = 0,
                .bufferImageHeight = 0,
                .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                .imageOffset = {0, 0, 0},
                .imageExtent = {(uint32_t) width, (uint32_t) height, 1},
        };
        vkCmdCopyBufferToImage(cmd, staging, openingImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                               1, &copy);
        VkImageMemoryBarrier toSample{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_SHADER_READ_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                .newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = openingImage,
                .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
        };
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                             &toSample);
        vkEndCommandBuffer(cmd);

        VkSubmitInfo submit{
                .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                .pNext = nullptr,
                .waitSemaphoreCount = 0,
                .pWaitSemaphores = nullptr,
                .pWaitDstStageMask = nullptr,
                .commandBufferCount = 1,
                .pCommandBuffers = &cmd,
                .signalSemaphoreCount = 0,
                .pSignalSemaphores = nullptr,
        };
        VkFenceCreateInfo uploadFenceInfo{.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, .pNext = nullptr, .flags = 0};
        if (!check(vkCreateFence(device, &uploadFenceInfo, nullptr, &openingUploadFence), "openingUploadFence")) {
            vkFreeCommandBuffers(device, cmdPool, 1, &cmd);
            destroyStaging();
            destroyOpeningResources();
            return false;
        }
        bool uploaded;
        {
            QueueGuard qlock(shared);
            uploaded = check(vkQueueSubmit(graphicsQueue, 1, &submit, openingUploadFence), "openingUploadSubmit");
        }
        if (!uploaded) {
            vkDestroyFence(device, openingUploadFence, nullptr);
            openingUploadFence = VK_NULL_HANDLE;
            vkFreeCommandBuffers(device, cmdPool, 1, &cmd);
            destroyStaging();
            destroyOpeningResources();
            return false;
        }
        // Keep upload resources until this submission's fence signals; never idle the queue.
        openingStaging = staging;
        openingStagingMemory = stagingMemory;
        openingUploadCmd = cmd;
        openingWidth = width;
        openingHeight = height;
        return true;
    }

    bool Engine::Impl::retireOpeningUpload(bool wait) {
        if (!openingUploadFence) return true;
        VkResult result = wait ? vkWaitForFences(device, 1, &openingUploadFence, VK_TRUE, UINT64_MAX)
                               : vkGetFenceStatus(device, openingUploadFence);
        if (result == VK_NOT_READY) return false;
        if (result != VK_SUCCESS && result != VK_ERROR_DEVICE_LOST) return false;
        vkFreeCommandBuffers(device, cmdPool, 1, &openingUploadCmd);
        vkDestroyBuffer(device, openingStaging, nullptr);
        vkFreeMemory(device, openingStagingMemory, nullptr);
        vkDestroyFence(device, openingUploadFence, nullptr);
        openingUploadCmd = VK_NULL_HANDLE;
        openingStaging = VK_NULL_HANDLE;
        openingStagingMemory = VK_NULL_HANDLE;
        openingUploadFence = VK_NULL_HANDLE;
        return true;
    }

    void Engine::Impl::destroyOpeningResources() {
        retireOpeningUpload(true);
        if (openingDescSet && descPool) {
            vkFreeDescriptorSets(device, descPool, 1, &openingDescSet);
            openingDescSet = VK_NULL_HANDLE;
        }
        if (openingPipeline) {
            vkDestroyPipeline(device, openingPipeline, nullptr);
            openingPipeline = VK_NULL_HANDLE;
        }
        if (openingPipelineLayout) {
            vkDestroyPipelineLayout(device, openingPipelineLayout, nullptr);
            openingPipelineLayout = VK_NULL_HANDLE;
        }
        if (openingSetLayout) {
            vkDestroyDescriptorSetLayout(device, openingSetLayout, nullptr);
            openingSetLayout = VK_NULL_HANDLE;
        }
        if (openingSampler) {
            vkDestroySampler(device, openingSampler, nullptr);
            openingSampler = VK_NULL_HANDLE;
        }
        if (openingView) {
            vkDestroyImageView(device, openingView, nullptr);
            openingView = VK_NULL_HANDLE;
        }
        if (openingImage) {
            vkDestroyImage(device, openingImage, nullptr);
            openingImage = VK_NULL_HANDLE;
        }
        if (openingMemory) {
            vkFreeMemory(device, openingMemory, nullptr);
            openingMemory = VK_NULL_HANDLE;
        }
        openingWidth = 0;
        openingHeight = 0;
    }


    bool Engine::Impl::ensureYcbcrResources(
            const VkAndroidHardwareBufferFormatPropertiesANDROID &fmtProps,
            bool externalFormat) {
        const uint64_t ext = externalFormat ? fmtProps.externalFormat : 0;
        const VkFormat fmt = externalFormat ? VK_FORMAT_UNDEFINED : fmtProps.format;
        if (ycbcrSampler && setLayout && pipelineLayout &&
            cachedExternalFormat == ext && cachedAhbFormat == fmt) {
            return true;
        }

        destroyYcbcrResources();

        VkExternalFormatANDROID extFormat{
                .sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID,
                .pNext = nullptr,
                .externalFormat = fmtProps.externalFormat,
        };
        VkSamplerYcbcrConversionCreateInfo yci{
                .sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO,
                .pNext = externalFormat ? &extFormat : nullptr,
                .format = fmt,
                .ycbcrModel = fmtProps.suggestedYcbcrModel,
                .ycbcrRange = fmtProps.suggestedYcbcrRange,
                .components = fmtProps.samplerYcbcrConversionComponents,
                .xChromaOffset = fmtProps.suggestedXChromaOffset,
                .yChromaOffset = fmtProps.suggestedYChromaOffset,
                .chromaFilter = VK_FILTER_LINEAR,
                .forceExplicitReconstruction = VK_FALSE,
        };
        if (!shared || !shared->fnCreateYcbcr ||
            !check(shared->fnCreateYcbcr(device, &yci, nullptr, &ycbcrConv),
                   "ycbcrConversion")) {
            return false;
        }

        VkSamplerYcbcrConversionInfo ycbcrInfo{
                .sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO,
                .pNext = nullptr,
                .conversion = ycbcrConv,
        };
        VkSamplerCreateInfo sci{
                .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
                .pNext = &ycbcrInfo,
                .flags = 0,
                .magFilter = VK_FILTER_LINEAR,
                .minFilter = VK_FILTER_LINEAR,
                .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
                .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                .mipLodBias = 0.f,
                .anisotropyEnable = VK_FALSE,
                .maxAnisotropy = 1.f,
                .compareEnable = VK_FALSE,
                .compareOp = VK_COMPARE_OP_NEVER,
                .minLod = 0.f,
                .maxLod = 0.f,
                .borderColor = VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK,
                .unnormalizedCoordinates = VK_FALSE,
        };
        if (!check(vkCreateSampler(device, &sci, nullptr, &ycbcrSampler), "ycbcrSampler")) {
            destroyYcbcrResources();
            return false;
        }

        VkSampler immutable = ycbcrSampler;
        VkDescriptorSetLayoutBinding binding{
                .binding = 0,
                .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                .descriptorCount = 1,
                .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
                .pImmutableSamplers = &immutable,
        };
        VkDescriptorSetLayoutCreateInfo slci{
                .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
                .pNext = nullptr,
                .flags = shared->pushDescriptors
                                 ? VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR
                                 : (VkDescriptorSetLayoutCreateFlags) 0,
                .bindingCount = 1,
                .pBindings = &binding,
        };
        if (!check(vkCreateDescriptorSetLayout(device, &slci, nullptr, &setLayout), "setLayout")) {
            destroyYcbcrResources();
            return false;
        }
        VkPipelineLayoutCreateInfo plci{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .setLayoutCount = 1,
                .pSetLayouts = &setLayout,
                .pushConstantRangeCount = 0,
                .pPushConstantRanges = nullptr,
        };
        if (!check(vkCreatePipelineLayout(device, &plci, nullptr, &pipelineLayout),
                   "pipelineLayout")) {
            destroyYcbcrResources();
            return false;
        }

        cachedExternalFormat = ext;
        cachedAhbFormat = fmt;
        if (!ensureGraphicsPipeline()) return false;
        return allocateFrameDescSets();
    }


    bool Engine::Impl::createVertexBuffer() {
        const auto &a = params.alphaUv;
        const auto &r = params.rgbUv;
        // Triangle strip: NDC Y+ is the top of the WindowSurface. ImageReader AHB is sampled
        // with V=0 at the top of memory, but MediaCodec PRIVATE buffers on this path are stored
        // bottom-up relative to Bitmap / PAG, so V is flipped (y0 ↔ y1) to match retriever frame 0.
        // 三角带：NDC Y+ 为窗口顶部。ImageReader AHB 的 V=0 在内存顶端，但此路径上
        // MediaCodec PRIVATE 缓冲相对 Bitmap / PAG 是底向上的，因此翻转 V（y0 ↔ y1）
        // 与 retriever 第 0 帧对齐。
        const float verts[] = {
                -1.f, 1.f, a.x0, a.y1, r.x0, r.y1,
                -1.f, -1.f, a.x0, a.y0, r.x0, r.y0,
                1.f, 1.f, a.x1, a.y1, r.x1, r.y1,
                1.f, -1.f, a.x1, a.y0, r.x1, r.y0,
        };
        static_assert(sizeof(verts) / sizeof(float) == 4u * kVertexFloats);
        static_assert(sizeof(verts) == 4u * kVertexStride);
        VkDeviceSize size = sizeof(verts);
        VkBufferCreateInfo bci{
                .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .size = size,
                .usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
                .queueFamilyIndexCount = 0,
                .pQueueFamilyIndices = nullptr,
        };
        if (!check(vkCreateBuffer(device, &bci, nullptr, &vertexBuffer), "vertexBuffer"))
            return false;
        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(device, vertexBuffer, &req);
        VkPhysicalDeviceMemoryProperties memProps{};
        vkGetPhysicalDeviceMemoryProperties(physical, &memProps);
        uint32_t memType = UINT32_MAX;
        for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
            if ((req.memoryTypeBits & (1u << i)) &&
                (memProps.memoryTypes[i].propertyFlags &
                 (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
                (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
                memType = i;
                break;
            }
        }
        if (memType == UINT32_MAX) return false;
        VkMemoryAllocateInfo mai{
                .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                .pNext = nullptr,
                .allocationSize = req.size,
                .memoryTypeIndex = memType,
        };
        if (!check(vkAllocateMemory(device, &mai, nullptr, &vertexMemory), "vertexMemory"))
            return false;
        vkBindBufferMemory(device, vertexBuffer, vertexMemory, 0);
        void *mapped = nullptr;
        vkMapMemory(device, vertexMemory, 0, size, 0, &mapped);
        std::memcpy(mapped, verts, sizeof(verts));
        vkUnmapMemory(device, vertexMemory);
        return true;
    }


    void Engine::Impl::destroySwapchain() {
        waitRetireAllFrames();
        for (auto fb: framebuffers) vkDestroyFramebuffer(device, fb, nullptr);
        framebuffers.clear();
        for (auto v: swapViews) vkDestroyImageView(device, v, nullptr);
        swapViews.clear();
        swapImages.clear();
        if (swapchain) {
            vkDestroySwapchainKHR(device, swapchain, nullptr);
            swapchain = VK_NULL_HANDLE;
        }
        if (pipeline) {
            vkDestroyPipeline(device, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        if (openingPipeline) {
            vkDestroyPipeline(device, openingPipeline, nullptr);
            openingPipeline = VK_NULL_HANDLE;
        }
        if (seqPipeline) {
            vkDestroyPipeline(device, seqPipeline, nullptr);
            seqPipeline = VK_NULL_HANDLE;
        }
        if (renderPass) {
            vkDestroyRenderPass(device, renderPass, nullptr);
            renderPass = VK_NULL_HANDLE;
        }
    }

    bool Engine::Impl::ensureRenderPass(VkFormat format) {
        if (renderPass && swapFormat == format) return true;
        if (pipeline) {
            vkDestroyPipeline(device, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        if (openingPipeline) {
            vkDestroyPipeline(device, openingPipeline, nullptr);
            openingPipeline = VK_NULL_HANDLE;
        }
        if (seqPipeline) {
            vkDestroyPipeline(device, seqPipeline, nullptr);
            seqPipeline = VK_NULL_HANDLE;
        }
        if (renderPass) {
            vkDestroyRenderPass(device, renderPass, nullptr);
            renderPass = VK_NULL_HANDLE;
        }
        swapFormat = format;

        VkAttachmentDescription color{
                .flags = 0,
                .format = format,
                .samples = VK_SAMPLE_COUNT_1_BIT,
                .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR,
                .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
                .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
                .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
                .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                .finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
        };
        VkAttachmentReference colorRef{.attachment = 0, .layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription subpass{
                .flags = 0,
                .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
                .inputAttachmentCount = 0,
                .pInputAttachments = nullptr,
                .colorAttachmentCount = 1,
                .pColorAttachments = &colorRef,
                .pResolveAttachments = nullptr,
                .pDepthStencilAttachment = nullptr,
                .preserveAttachmentCount = 0,
                .pPreserveAttachments = nullptr,
        };
        VkSubpassDependency dep{
                .srcSubpass = VK_SUBPASS_EXTERNAL,
                .dstSubpass = 0,
                .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                .srcAccessMask = 0,
                .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                .dependencyFlags = 0,
        };
        VkRenderPassCreateInfo rpci{
                .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .attachmentCount = 1,
                .pAttachments = &color,
                .subpassCount = 1,
                .pSubpasses = &subpass,
                .dependencyCount = 1,
                .pDependencies = &dep,
        };
        return check(vkCreateRenderPass(device, &rpci, nullptr, &renderPass), "renderPass");
    }

    bool Engine::Impl::createPipelineForLayout(VkPipelineLayout layout, VkPipeline &outPipeline,
                                               VkShaderModule fragOverride) {
        VkShaderModule frag = fragOverride == VK_NULL_HANDLE ? fragModule : fragOverride;
        if (!renderPass || !layout || !vertModule || !frag) return false;
        if (outPipeline) {
            vkDestroyPipeline(device, outPipeline, nullptr);
            outPipeline = VK_NULL_HANDLE;
        }

        VkPipelineShaderStageCreateInfo stages[2] = {
                {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                        .pNext = nullptr,
                        .flags = 0,
                        .stage = VK_SHADER_STAGE_VERTEX_BIT,
                        .module = vertModule,
                        .pName = "main",
                        .pSpecializationInfo = nullptr},
                {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                        .pNext = nullptr,
                        .flags = 0,
                        .stage = VK_SHADER_STAGE_FRAGMENT_BIT,
                        .module = frag,
                        .pName = "main",
                        .pSpecializationInfo = nullptr},
        };
        VkVertexInputBindingDescription bind{
                .binding = 0,
                .stride = kVertexStride,
                .inputRate = VK_VERTEX_INPUT_RATE_VERTEX,
        };
        VkVertexInputAttributeDescription attrs[3] = {
                {.location = 0, .binding = 0, .format = VK_FORMAT_R32G32_SFLOAT, .offset = kVertexOffsetPos},
                {.location = 1, .binding = 0, .format = VK_FORMAT_R32G32_SFLOAT, .offset = kVertexOffsetAlpha},
                {.location = 2, .binding = 0, .format = VK_FORMAT_R32G32_SFLOAT, .offset = kVertexOffsetRgb},
        };
        VkPipelineVertexInputStateCreateInfo vi{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .vertexBindingDescriptionCount = 1,
                .pVertexBindingDescriptions = &bind,
                .vertexAttributeDescriptionCount = 3,
                .pVertexAttributeDescriptions = attrs,
        };
        VkPipelineInputAssemblyStateCreateInfo ia{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP,
                .primitiveRestartEnable = VK_FALSE,
        };
        VkPipelineViewportStateCreateInfo vp{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .viewportCount = 1,
                .pViewports = nullptr,
                .scissorCount = 1,
                .pScissors = nullptr,
        };
        VkPipelineRasterizationStateCreateInfo rs{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .depthClampEnable = VK_FALSE,
                .rasterizerDiscardEnable = VK_FALSE,
                .polygonMode = VK_POLYGON_MODE_FILL,
                .cullMode = VK_CULL_MODE_NONE,
                .frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE,
                .depthBiasEnable = VK_FALSE,
                .depthBiasConstantFactor = 0.f,
                .depthBiasClamp = 0.f,
                .depthBiasSlopeFactor = 0.f,
                .lineWidth = 1.f,
        };
        VkPipelineMultisampleStateCreateInfo ms{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT,
                .sampleShadingEnable = VK_FALSE,
                .minSampleShading = 0.f,
                .pSampleMask = nullptr,
                .alphaToCoverageEnable = VK_FALSE,
                .alphaToOneEnable = VK_FALSE,
        };
        VkPipelineColorBlendAttachmentState blendAtt{
                .blendEnable = VK_FALSE,
                .srcColorBlendFactor = VK_BLEND_FACTOR_ONE,
                .dstColorBlendFactor = VK_BLEND_FACTOR_ZERO,
                .colorBlendOp = VK_BLEND_OP_ADD,
                .srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE,
                .dstAlphaBlendFactor = VK_BLEND_FACTOR_ZERO,
                .alphaBlendOp = VK_BLEND_OP_ADD,
                .colorWriteMask = static_cast<VkColorComponentFlags>(
                        params.hasAlpha
                        ? (VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                           VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                        // Opaque: keep clear-alpha=1; shader alpha is unused (aFrame is empty).
                        // 不透明：保留 clear 的 alpha=1；shader 的 alpha 无意义（aFrame 为空）。
                        : (VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                           VK_COLOR_COMPONENT_B_BIT)),
        };
        VkPipelineColorBlendStateCreateInfo blend{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .logicOpEnable = VK_FALSE,
                .logicOp = VK_LOGIC_OP_COPY,
                .attachmentCount = 1,
                .pAttachments = &blendAtt,
                .blendConstants = {0.f, 0.f, 0.f, 0.f},
        };
        VkDynamicState dynStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo dyn{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .dynamicStateCount = 2,
                .pDynamicStates = dynStates,
        };
        VkGraphicsPipelineCreateInfo gpci{
                .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .stageCount = 2,
                .pStages = stages,
                .pVertexInputState = &vi,
                .pInputAssemblyState = &ia,
                .pTessellationState = nullptr,
                .pViewportState = &vp,
                .pRasterizationState = &rs,
                .pMultisampleState = &ms,
                .pDepthStencilState = nullptr,
                .pColorBlendState = &blend,
                .pDynamicState = &dyn,
                .layout = layout,
                .renderPass = renderPass,
                .subpass = 0,
                .basePipelineHandle = VK_NULL_HANDLE,
                .basePipelineIndex = -1,
        };
        return check(
                vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &gpci, nullptr, &outPipeline),
                "graphicsPipeline");
    }

    bool Engine::Impl::ensureGraphicsPipeline() {
        return createPipelineForLayout(pipelineLayout, pipeline);
    }

    bool Engine::Impl::ensureOpeningPipeline() {
        return createPipelineForLayout(openingPipelineLayout, openingPipeline);
    }

    bool Engine::Impl::ensureSequencePipeline() {
        if (seqPipeline) return true;
        if (!renderPass || !seqPipelineLayout || !seqFragModule) return false;
        return createPipelineForLayout(seqPipelineLayout, seqPipeline, seqFragModule);
    }

    bool Engine::Impl::createSwapchain(int width, int height) {
        destroySwapchain();
        if (!surface || !window) return false;

        VkSurfaceCapabilitiesKHR caps{};
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, surface, &caps);
        uint32_t formatCount = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, &formatCount, nullptr);
        if (formatCount == 0) {
            VAP_LOGE("swapchain: no surface formats");
            return false;
        }
        std::vector<VkSurfaceFormatKHR> formats(formatCount);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, &formatCount, formats.data());
        VkSurfaceFormatKHR chosen = formats[0];
        for (auto &f: formats) {
            if (f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_B8G8R8A8_UNORM) {
                chosen = f;
                break;
            }
        }
        if (!ensureRenderPass(chosen.format)) return false;
        // Pipeline creation remains deferred until the first AHB determines its YCbCr format.
        // 管线仍延迟到首个 AHB 确定其 YCbCr 格式后创建。
        if (pipelineLayout && !ensureGraphicsPipeline()) return false;
        if (openingPipelineLayout && openingView && !ensureOpeningPipeline()) return false;
        if (seqPipelineLayout && seqView && !ensureSequencePipeline()) return false;

        swapExtent.width = (uint32_t) std::max(1, width);
        swapExtent.height = (uint32_t) std::max(1, height);
        if (caps.currentExtent.width != UINT32_MAX) {
            swapExtent = caps.currentExtent;
        } else {
            swapExtent.width = std::clamp(swapExtent.width, caps.minImageExtent.width,
                                          caps.maxImageExtent.width);
            swapExtent.height =
                    std::clamp(swapExtent.height, caps.minImageExtent.height,
                               caps.maxImageExtent.height);
        }

        uint32_t imageCount = caps.minImageCount + 1;
        if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount)
            imageCount = caps.maxImageCount;

        VkSwapchainCreateInfoKHR sci{
                .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
                .pNext = nullptr,
                .flags = 0,
                .surface = surface,
                .minImageCount = imageCount,
                .imageFormat = chosen.format,
                .imageColorSpace = chosen.colorSpace,
                .imageExtent = swapExtent,
                .imageArrayLayers = 1,
                .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
                .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
                .queueFamilyIndexCount = 0,
                .pQueueFamilyIndices = nullptr,
                // Draw is always identity NDC (window space), same as TextureView. SurfaceView
                // often reports a non-identity currentTransform; claiming we pre-rotated without
                // rotating verts swaps X/Y. Let the compositor rotate when needed.
                // 顶点始终按窗口坐标系的 identity NDC 画（与 TextureView 一致）。SurfaceView
                // 常上报非 identity 的 currentTransform；若声称已预旋转却不转顶点，X/Y 会对调。
                // 需要旋转时交给合成器。
                .preTransform = ((caps.supportedTransforms &
                                  VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) != 0)
                        ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                        : caps.currentTransform,
                .compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
                .presentMode = VK_PRESENT_MODE_FIFO_KHR,
                .clipped = VK_TRUE,
                .oldSwapchain = VK_NULL_HANDLE,
        };
        // Android lists every composite-alpha bit as supported. Prefer INHERIT so a
        // TRANSLUCENT SurfaceView keeps real alpha; PRE_MULTIPLIED here turns
        // transparent pixels into a black backdrop (float-window first-frame still
        // looks correct because it is drawn in Compose).
        // Android 会把所有 composite-alpha 都标成支持。优先 INHERIT，让 TRANSLUCENT
        // SurfaceView 保住真 Alpha；若选 PRE_MULTIPLIED，透明像素会变成黑底（悬浮窗
        // 第 0 帧看起来正常，是因为画在 Compose 里）。
        if (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR) {
            sci.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
        } else if (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR) {
            sci.compositeAlpha = VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR;
        } else if (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR) {
            sci.compositeAlpha = VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR;
        } else if (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) {
            sci.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        }
        if (!check(vkCreateSwapchainKHR(device, &sci, nullptr, &swapchain), "swapchain"))
            return false;

        uint32_t imgCount = 0;
        vkGetSwapchainImagesKHR(device, swapchain, &imgCount, nullptr);
        swapImages.resize(imgCount);
        vkGetSwapchainImagesKHR(device, swapchain, &imgCount, swapImages.data());
        swapViews.resize(imgCount);
        framebuffers.resize(imgCount);
        for (uint32_t i = 0; i < imgCount; i++) {
            VkImageViewCreateInfo vci{
                    .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
                    .pNext = nullptr,
                    .flags = 0,
                    .image = swapImages[i],
                    .viewType = VK_IMAGE_VIEW_TYPE_2D,
                    .format = chosen.format,
                    .components = {},
                    .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
            };
            if (!check(vkCreateImageView(device, &vci, nullptr, &swapViews[i]), "swapView"))
                return false;
            VkFramebufferCreateInfo fci{
                    .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
                    .pNext = nullptr,
                    .flags = 0,
                    .renderPass = renderPass,
                    .attachmentCount = 1,
                    .pAttachments = &swapViews[i],
                    .width = swapExtent.width,
                    .height = swapExtent.height,
                    .layers = 1,
            };
            if (!check(vkCreateFramebuffer(device, &fci, nullptr, &framebuffers[i]),
                       "framebuffer")) {
                return false;
            }
        }
        swapchainInvalid = false;
        VAP_LOGI("Swapchain %ux%u format=%d images=%u currentXform=0x%x preXform=0x%x composite=0x%x",
                 swapExtent.width, swapExtent.height, (int) chosen.format, imgCount,
                 (unsigned) caps.currentTransform, (unsigned) sci.preTransform,
                 (unsigned) sci.compositeAlpha);
        return true;
    }


    bool Engine::Impl::acquireAhbImport(
            AHardwareBuffer *buffer,
            uint64_t bufferId,
            const VkAndroidHardwareBufferFormatPropertiesANDROID &fmtProps,
            const VkAndroidHardwareBufferPropertiesANDROID &ahbProps,
            bool externalFormat,
            AhbImport &out) {
        destroyAhbImport(out);
        out.generation = importPool.generation();

        // Reuse a pooled import when the ImageReader ring returns an AHB we already imported.
        // The pooled entry already holds the AHB reference, so no acquire/import runs here.
        // 当 ImageReader 环形队列再次返回已导入过的 AHB 时复用池中导入。池中条目已持有
        // AHB 引用，因此这里不再 acquire/import。
        if (takePooledImport(reinterpret_cast<uintptr_t>(buffer), out)) {
            out.bufferId = bufferId; // Diagnostics only. 仅用于诊断。
            return true;
        }

        // All AHBs in one ImageReader stream share the description resolved by resolveAhbProps,
        // so reuse the cached copy instead of describing the buffer on every import.
        // 同一 ImageReader 流内的 AHB 共享 resolveAhbProps 解析出的描述，复用缓存副本，
        // 不再每次导入都解析缓冲区。
        AHardwareBuffer_Desc desc{};
        if (ahbPropsCached) {
            desc = cachedAhbDesc;
        } else {
            AHardwareBuffer_describe(buffer, &desc);
        }

        AHardwareBuffer_acquire(buffer);
        out.ahb = buffer;
        // The import pool is keyed by the AHB pointer, not by bufferId, because
        // HardwareBuffer.getId() is a stubbed 0 on the target devices; bufferId is kept for
        // diagnostics only.
        // 导入池以 AHB 指针为键，而非 bufferId，因为目标设备 HardwareBuffer.getId() 恒为 0；
        // bufferId 仅用于诊断。
        out.bufferId = bufferId;

        VkExternalFormatANDROID extFormat{
                .sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID,
                .pNext = nullptr,
                .externalFormat = fmtProps.externalFormat,
        };
        VkExternalMemoryImageCreateInfo extMemImg{
                .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
                .pNext = externalFormat ? &extFormat : nullptr,
                .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID,
        };
        VkImageCreateInfo ici{
                .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
                .pNext = &extMemImg,
                .flags = 0,
                .imageType = VK_IMAGE_TYPE_2D,
                .format = externalFormat ? VK_FORMAT_UNDEFINED : fmtProps.format,
                .extent = {desc.width, desc.height, 1},
                .mipLevels = 1,
                .arrayLayers = desc.layers ? desc.layers : 1,
                .samples = VK_SAMPLE_COUNT_1_BIT,
                .tiling = VK_IMAGE_TILING_OPTIMAL,
                .usage = VK_IMAGE_USAGE_SAMPLED_BIT,
                .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
                .queueFamilyIndexCount = 0,
                .pQueueFamilyIndices = nullptr,
                .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        };
        if (!check(vkCreateImage(device, &ici, nullptr, &out.image), "createAhbImage")) {
            destroyAhbImport(out);
            return false;
        }

        // Resolve the dedicated-memory requirement once per stream: image create params are
        // stable, so the driver's answer cannot change between imports.
        // 每个流只解析一次专用内存要求：图像创建参数稳定，驱动的答案在多次导入间不会改变。
        bool needsDedicated;
        if (ahbImportReqsCached) {
            needsDedicated = cachedAhbNeedsDedicated;
        } else {
            // Query whether this image requires dedicated memory. Unconditionally chaining
            // VkMemoryDedicatedAllocateInfo makes some Adreno drivers privately duplicate an
            // entire AHB per import, so chain it only when required.
            // 查询该图像是否必须使用专用内存。无条件串接 VkMemoryDedicatedAllocateInfo 会使
            // 部分 Adreno 驱动在每次导入时私下复制整块 AHB，因此仅在 required 时串接。
            VkMemoryDedicatedRequirements dedicatedReqs{
                    .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_REQUIREMENTS,
                    .pNext = nullptr,
                    .prefersDedicatedAllocation = VK_FALSE,
                    .requiresDedicatedAllocation = VK_FALSE,
            };
            VkMemoryRequirements2 memReqs2{
                    .sType = VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2,
                    .pNext = &dedicatedReqs,
                    .memoryRequirements = {},
            };
            VkImageMemoryRequirementsInfo2 reqInfo{
                    .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_REQUIREMENTS_INFO_2,
                    .pNext = nullptr,
                    .image = out.image,
            };
            vkGetImageMemoryRequirements2(device, &reqInfo, &memReqs2);
            if (!ahbDedicatedLogged) {
                ahbDedicatedLogged = true;
                VAP_LOGI("ahb import dedicated: required=%d preferred=%d",
                         (int) dedicatedReqs.requiresDedicatedAllocation,
                         (int) dedicatedReqs.prefersDedicatedAllocation);
            }
            needsDedicated = dedicatedReqs.requiresDedicatedAllocation == VK_TRUE;
            cachedAhbNeedsDedicated = needsDedicated;
        }

        VkImportAndroidHardwareBufferInfoANDROID importInfo{
                .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
                .pNext = nullptr,
                .buffer = buffer,
        };
        VkMemoryDedicatedAllocateInfo dedicated{
                .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
                .pNext = &importInfo,
                .image = out.image,
                .buffer = VK_NULL_HANDLE,
        };
        VkMemoryAllocateInfo mai{
                .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                .pNext = needsDedicated ? (const void *) &dedicated : (const void *) &importInfo,
                .allocationSize = ahbProps.allocationSize,
                .memoryTypeIndex = 0,
        };
        if (ahbImportReqsCached) {
            // The compatible memory type derives from the cached allocation size/type bits and
            // the (stable) physical-device memory properties, so reuse the resolved index.
            // 兼容的内存类型由缓存的分配大小/类型位与（稳定的）物理设备内存属性推导，
            // 因此复用已解析的索引。
            mai.memoryTypeIndex = cachedAhbMemoryTypeIndex;
        } else {
            VkPhysicalDeviceMemoryProperties localMemProps{};
            const VkPhysicalDeviceMemoryProperties *memProps =
                    (shared && shared->memoryPropsReady) ? &shared->memoryProps : nullptr;
            if (!memProps) {
                vkGetPhysicalDeviceMemoryProperties(physical, &localMemProps);
                memProps = &localMemProps;
            }
            for (uint32_t i = 0; i < memProps->memoryTypeCount; i++) {
                if (ahbProps.memoryTypeBits & (1u << i)) {
                    mai.memoryTypeIndex = i;
                    break;
                }
            }
            cachedAhbMemoryTypeIndex = mai.memoryTypeIndex;
            ahbImportReqsCached = true;
        }
        if (!check(vkAllocateMemory(device, &mai, nullptr, &out.memory), "allocAhbMemory")) {
            destroyAhbImport(out);
            return false;
        }
        vkBindImageMemory(device, out.image, out.memory, 0);

        VkSamplerYcbcrConversionInfo ycbcrInfo{
                .sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO,
                .pNext = nullptr,
                .conversion = ycbcrConv,
        };
        VkImageViewCreateInfo vci{
                .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
                .pNext = &ycbcrInfo,
                .flags = 0,
                .image = out.image,
                .viewType = VK_IMAGE_VIEW_TYPE_2D,
                .format = externalFormat ? VK_FORMAT_UNDEFINED : fmtProps.format,
                .components = {},
                .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
        };
        if (!check(vkCreateImageView(device, &vci, nullptr, &out.view), "srcView")) {
            destroyAhbImport(out);
            return false;
        }
        return true;
    }

    void Engine::Impl::bindSlotImport(FrameSlot &slot, const AhbImport &imp) {
        if (slot.boundView == imp.view) return; // Descriptor already samples this view.
                                                // 描述符已采样该视图。
        if (shared->pushDescriptors) {
            // Push-descriptor mode writes the current view later in recordDrawCommands.
            // 推送描述符模式会稍后在 recordDrawCommands 中写入当前视图。
            slot.boundView = imp.view;
            slot.boundAhb = imp.ahb;
            return;
        }
        VkDescriptorImageInfo dii{
                .sampler = VK_NULL_HANDLE,
                .imageView = imp.view,
                .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        };
        VkWriteDescriptorSet write{
                .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                .pNext = nullptr,
                .dstSet = slot.descSet,
                .dstBinding = 0,
                .dstArrayElement = 0,
                .descriptorCount = 1,
                .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                .pImageInfo = &dii,
                .pBufferInfo = nullptr,
                .pTexelBufferView = nullptr,
        };
        vkUpdateDescriptorSets(device, 1, &write, 0, nullptr);
        slot.boundView = imp.view;
        slot.boundAhb = imp.ahb;
    }

    bool Engine::Impl::drainAcquireSemaphores() {
        VkSemaphore waits[kFramesInFlight];
        VkPipelineStageFlags stages[kFramesInFlight];
        FrameSlot *owned[kFramesInFlight];
        int n = 0;
        for (auto &frame: frames) {
            if (!frame.acquireSignaled) continue;
            waits[n] = frame.imageAvailable;
            stages[n] = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
            owned[n] = &frame;
            n++;
        }
        if (n == 0) return true;
        if (!acquireFence ||
            !check(vkResetFences(device, 1, &acquireFence), "resetDrainFence")) {
            return false;
        }
        VkSubmitInfo drain{
                .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                .pNext = nullptr,
                .waitSemaphoreCount = (uint32_t) n,
                .pWaitSemaphores = waits,
                .pWaitDstStageMask = stages,
                .commandBufferCount = 0,
                .pCommandBuffers = nullptr,
                .signalSemaphoreCount = 0,
                .pSignalSemaphores = nullptr,
        };
        {
            QueueGuard qlock(shared);
            if (!check(vkQueueSubmit(graphicsQueue, 1, &drain, acquireFence), "drainAcquire")) {
                return false;
            }
        }
        if (!check(vkWaitForFences(device, 1, &acquireFence, VK_TRUE, UINT64_MAX),
                   "waitDrainAcquire")) {
            return false;
        }
        for (int i = 0; i < n; i++) owned[i]->acquireSignaled = false;
        return true;
    }

    bool Engine::Impl::resolveAhbProps(
            AHardwareBuffer *buffer,
            VkAndroidHardwareBufferFormatPropertiesANDROID &fmtProps,
            VkAndroidHardwareBufferPropertiesANDROID &ahbProps) {
        // All buffers in an ImageReader stream share one description, and format changes recreate
        // the session. After the first query, reuse these properties and even skip per-frame
        // AHardwareBuffer_describe; repair pNext because the cached copy referenced old stack data.
        // 同一 ImageReader 流的缓冲区共享描述，格式变化会重建会话。首次查询后复用属性，
        // 连每帧 AHardwareBuffer_describe 也跳过；缓存副本曾指向旧栈数据，需修复 pNext。
        if (ahbPropsCached) {
            fmtProps = cachedFmtProps;
            ahbProps = cachedAhbProps;
            ahbProps.pNext = &fmtProps; // Cached copy pointed to the previous stack object.
                                        // 缓存副本原先指向旧栈对象。
            return true;
        }
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(buffer, &desc);
        fmtProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
        ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
        ahbProps.pNext = &fmtProps;
        if (!shared || !shared->fnGetAhbProps ||
            !check(shared->fnGetAhbProps(device, buffer, &ahbProps), "GetAhbProps")) {
            return false;
        }
        cachedFmtProps = fmtProps;
        cachedAhbProps = ahbProps;
        cachedAhbProps.pNext = nullptr;
        cachedAhbDesc = desc;
        ahbPropsCached = true;
        return true;
    }


    void Engine::Impl::recordDrawCommands(FrameSlot &slot, VkImage srcImage,
                                          VkImageView srcView, uint32_t imageIndex) {
        vkResetCommandBuffer(slot.cmd, 0);
        VkCommandBufferBeginInfo begin{
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                .pNext = nullptr,
                .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
                .pInheritanceInfo = nullptr,
        };
        vkBeginCommandBuffer(slot.cmd, &begin);

        // Acquire the imported AHB from the Android foreign queue family and make it readable by
        // fragment sampling before the draw records any texture access.
        // 从 Android 外部队列族接管导入的 AHB，并在绘制发生纹理访问前使其对片元采样可读。
        VkImageMemoryBarrier toSample{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = 0,
                .dstAccessMask = VK_ACCESS_SHADER_READ_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                .newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT,
                .dstQueueFamilyIndex = graphicsQueueFamily,
                .image = srcImage,
                .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
        };
        vkCmdPipelineBarrier(slot.cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                             &toSample);

        VkClearValue clear{.color = {{0.f, 0.f, 0.f, params.hasAlpha ? 0.f : 1.f}}};
        VkRenderPassBeginInfo rpBegin{
                .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
                .pNext = nullptr,
                .renderPass = renderPass,
                .framebuffer = framebuffers[imageIndex],
                .renderArea = {{0, 0}, swapExtent},
                .clearValueCount = 1,
                .pClearValues = &clear,
        };
        vkCmdBeginRenderPass(slot.cmd, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        VkViewport viewport{0, 0, (float) swapExtent.width, (float) swapExtent.height, 0.f, 1.f};
        VkRect2D scissor{{0, 0}, swapExtent};
        vkCmdSetViewport(slot.cmd, 0, 1, &viewport);
        vkCmdSetScissor(slot.cmd, 0, 1, &scissor);
        VkDeviceSize offset = 0;
        vkCmdBindVertexBuffers(slot.cmd, 0, 1, &vertexBuffer, &offset);
        if (shared->pushDescriptors) {
            // Embed the sampled view in the command buffer, replacing per-frame descriptor-set
            // updates and the pool; the descriptor layout supplies the immutable YCbCr sampler.
            // 将采样视图直接嵌入命令缓冲，替代逐帧描述符集更新及描述符池；
            // 不可变 YCbCr 采样器由描述符布局提供。
            VkDescriptorImageInfo dii{
                    .sampler = VK_NULL_HANDLE,
                    .imageView = srcView,
                    .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            };
            VkWriteDescriptorSet write{
                    .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                    .pNext = nullptr,
                    .dstSet = VK_NULL_HANDLE,
                    .dstBinding = 0,
                    .dstArrayElement = 0,
                    .descriptorCount = 1,
                    .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                    .pImageInfo = &dii,
                    .pBufferInfo = nullptr,
                    .pTexelBufferView = nullptr,
            };
            shared->fnPushDescriptor(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                     pipelineLayout, 0, 1, &write);
        } else {
            vkCmdBindDescriptorSets(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout,
                                    0, 1, &slot.descSet, 0, nullptr);
        }
        vkCmdDraw(slot.cmd, 4, 1, 0, 0);
        vkCmdEndRenderPass(slot.cmd);
        vkEndCommandBuffer(slot.cmd);
    }

    void Engine::Impl::recordSequenceCommands(FrameSlot &slot, uint32_t imageIndex,
                                              uint32_t layer) {
        vkResetCommandBuffer(slot.cmd, 0);
        VkCommandBufferBeginInfo begin{
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                .pNext = nullptr,
                .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
                .pInheritanceInfo = nullptr,
        };
        vkBeginCommandBuffer(slot.cmd, &begin);

        VkClearValue clear{.color = {{0.f, 0.f, 0.f, params.hasAlpha ? 0.f : 1.f}}};
        VkRenderPassBeginInfo rpBegin{
                .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
                .pNext = nullptr,
                .renderPass = renderPass,
                .framebuffer = framebuffers[imageIndex],
                .renderArea = {{0, 0}, swapExtent},
                .clearValueCount = 1,
                .pClearValues = &clear,
        };
        vkCmdBeginRenderPass(slot.cmd, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, seqPipeline);
        VkViewport viewport{0, 0, (float) swapExtent.width, (float) swapExtent.height, 0.f, 1.f};
        VkRect2D scissor{{0, 0}, swapExtent};
        vkCmdSetViewport(slot.cmd, 0, 1, &viewport);
        vkCmdSetScissor(slot.cmd, 0, 1, &scissor);
        VkDeviceSize offset = 0;
        vkCmdBindVertexBuffers(slot.cmd, 0, 1, &vertexBuffer, &offset);
        vkCmdBindDescriptorSets(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, seqPipelineLayout,
                                0, 1, &seqDescSet, 0, nullptr);
        vkCmdPushConstants(slot.cmd, seqPipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT,
                           0, sizeof(layer), &layer);
        vkCmdDraw(slot.cmd, 4, 1, 0, 0);
        vkCmdEndRenderPass(slot.cmd);
        vkEndCommandBuffer(slot.cmd);
    }

    void Engine::Impl::recordOpeningCommands(FrameSlot &slot, uint32_t imageIndex) {
        vkResetCommandBuffer(slot.cmd, 0);
        VkCommandBufferBeginInfo begin{
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                .pNext = nullptr,
                .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
                .pInheritanceInfo = nullptr,
        };
        vkBeginCommandBuffer(slot.cmd, &begin);

        VkClearValue clear{.color = {{0.f, 0.f, 0.f, params.hasAlpha ? 0.f : 1.f}}};
        VkRenderPassBeginInfo rpBegin{
                .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
                .pNext = nullptr,
                .renderPass = renderPass,
                .framebuffer = framebuffers[imageIndex],
                .renderArea = {{0, 0}, swapExtent},
                .clearValueCount = 1,
                .pClearValues = &clear,
        };
        vkCmdBeginRenderPass(slot.cmd, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, openingPipeline);
        VkViewport viewport{0, 0, (float) swapExtent.width, (float) swapExtent.height, 0.f, 1.f};
        VkRect2D scissor{{0, 0}, swapExtent};
        vkCmdSetViewport(slot.cmd, 0, 1, &viewport);
        vkCmdSetScissor(slot.cmd, 0, 1, &scissor);
        VkDeviceSize offset = 0;
        vkCmdBindVertexBuffers(slot.cmd, 0, 1, &vertexBuffer, &offset);
        if (shared->pushDescriptors) {
            VkDescriptorImageInfo dii{
                    .sampler = openingSampler,
                    .imageView = openingView,
                    .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            };
            VkWriteDescriptorSet write{
                    .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                    .pNext = nullptr,
                    .dstSet = VK_NULL_HANDLE,
                    .dstBinding = 0,
                    .dstArrayElement = 0,
                    .descriptorCount = 1,
                    .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                    .pImageInfo = &dii,
                    .pBufferInfo = nullptr,
                    .pTexelBufferView = nullptr,
            };
            shared->fnPushDescriptor(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                     openingPipelineLayout, 0, 1, &write);
        } else {
            vkCmdBindDescriptorSets(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                    openingPipelineLayout, 0, 1, &openingDescSet, 0, nullptr);
        }
        vkCmdDraw(slot.cmd, 4, 1, 0, 0);
        vkCmdEndRenderPass(slot.cmd);
        vkEndCommandBuffer(slot.cmd);
    }

    void Engine::Impl::recordClearCommands(FrameSlot &slot, uint32_t imageIndex) {
        vkResetCommandBuffer(slot.cmd, 0);
        VkCommandBufferBeginInfo begin{
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                .pNext = nullptr,
                .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
                .pInheritanceInfo = nullptr,
        };
        vkBeginCommandBuffer(slot.cmd, &begin);
        VkClearValue clear{.color = {{0.f, 0.f, 0.f, 0.f}}};
        VkRenderPassBeginInfo rpBegin{
                .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
                .pNext = nullptr,
                .renderPass = renderPass,
                .framebuffer = framebuffers[imageIndex],
                .renderArea = {{0, 0}, swapExtent},
                .clearValueCount = 1,
                .pClearValues = &clear,
        };
        vkCmdBeginRenderPass(slot.cmd, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdEndRenderPass(slot.cmd);
        vkEndCommandBuffer(slot.cmd);
    }


    bool Engine::Impl::presentClear() {
        if (!ready() || !params.hasAlpha || !renderPass || framebuffers.empty()) return false;
        FrameSlot &slot = frames[frameIndex];
        if (!waitRetireFrame(slot)) return false;
        if (!check(vkResetFences(device, 1, &slot.fence), "resetClearFence")) return false;

        uint32_t imageIndex = 0;
        VkResult acq = vkAcquireNextImageKHR(device, swapchain, UINT64_MAX, slot.imageAvailable,
                                             VK_NULL_HANDLE, &imageIndex);
        if (acq == VK_ERROR_OUT_OF_DATE_KHR || acq == VK_SUBOPTIMAL_KHR) {
            waitRetireAllFrames();
            if (!createSwapchain((int) swapExtent.width, (int) swapExtent.height)) return false;
            if (!check(vkResetFences(device, 1, &slot.fence), "resetClearFenceRetry")) return false;
            acq = vkAcquireNextImageKHR(device, swapchain, UINT64_MAX, slot.imageAvailable,
                                        VK_NULL_HANDLE, &imageIndex);
        }
        if (acq != VK_SUCCESS && acq != VK_SUBOPTIMAL_KHR) {
            VAP_LOGE("clear acquire failed %d", (int) acq);
            return false;
        }

        recordClearCommands(slot, imageIndex);
        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit{
                .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                .pNext = nullptr,
                .waitSemaphoreCount = 1,
                .pWaitSemaphores = &slot.imageAvailable,
                .pWaitDstStageMask = &waitStage,
                .commandBufferCount = 1,
                .pCommandBuffers = &slot.cmd,
                .signalSemaphoreCount = 1,
                .pSignalSemaphores = &slot.renderFinished,
        };
        VkResult pr = VK_SUCCESS;
        {
            QueueGuard qlock(shared);
            if (!check(vkQueueSubmit(graphicsQueue, 1, &submit, slot.fence), "clearQueueSubmit")) {
                return false;
            }
            VkPresentInfoKHR present{
                    .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
                    .pNext = nullptr,
                    .waitSemaphoreCount = 1,
                    .pWaitSemaphores = &slot.renderFinished,
                    .swapchainCount = 1,
                    .pSwapchains = &swapchain,
                    .pImageIndices = &imageIndex,
                    .pResults = nullptr,
            };
            pr = vkQueuePresentKHR(graphicsQueue, &present);
        }
        slot.submitted = true;
        frameIndex = (frameIndex + 1) % kFramesInFlight;
        if (pr == VK_ERROR_OUT_OF_DATE_KHR || pr == VK_SUBOPTIMAL_KHR) {
            waitRetireAllFrames();
            createSwapchain((int) swapExtent.width, (int) swapExtent.height);
            return false;
        }
        return pr == VK_SUCCESS;
    }

    bool Engine::Impl::presentOpening() {
        if (!ready() || !openingView || !openingPipelineLayout) return false;
        if (!retireOpeningUpload(false)) return false;
        if (!openingPipeline && !ensureOpeningPipeline()) return false;

        FrameSlot &slot = frames[frameIndex];
        VkFence completion = slot.batch ? slot.batch->fence : slot.fence;
        if ((slot.batch || slot.submitted) && vkGetFenceStatus(device, completion) == VK_NOT_READY) return false;
        if (!waitRetireFrame(slot)) return false;

        uint32_t imageIndex = 0;
        VkResult acq = vkAcquireNextImageKHR(device, swapchain, 0, slot.imageAvailable, VK_NULL_HANDLE, &imageIndex);
        if (acq == VK_ERROR_OUT_OF_DATE_KHR) swapchainInvalid = true;
        if (acq != VK_SUCCESS && acq != VK_SUBOPTIMAL_KHR) return false;
        slot.acquireSignaled = true;
        if (!check(vkResetFences(device, 1, &slot.fence), "resetOpeningFence")) return false;

        recordOpeningCommands(slot, imageIndex);
        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit{
                .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                .pNext = nullptr,
                .waitSemaphoreCount = 1,
                .pWaitSemaphores = &slot.imageAvailable,
                .pWaitDstStageMask = &waitStage,
                .commandBufferCount = 1,
                .pCommandBuffers = &slot.cmd,
                .signalSemaphoreCount = 1,
                .pSignalSemaphores = &slot.renderFinished,
        };
        VkResult pr = VK_SUCCESS;
        {
            QueueGuard qlock(shared);
            if (!check(vkQueueSubmit(graphicsQueue, 1, &submit, slot.fence), "openingQueueSubmit")) {
                return false;
            }
            VkPresentInfoKHR present{
                    .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
                    .pNext = nullptr,
                    .waitSemaphoreCount = 1,
                    .pWaitSemaphores = &slot.renderFinished,
                    .swapchainCount = 1,
                    .pSwapchains = &swapchain,
                    .pImageIndices = &imageIndex,
                    .pResults = nullptr,
            };
            pr = vkQueuePresentKHR(graphicsQueue, &present);
        }
        slot.acquireSignaled = false;
        slot.submitted = true;
        frameIndex = (frameIndex + 1) % kFramesInFlight;
        if (pr == VK_ERROR_OUT_OF_DATE_KHR) swapchainInvalid = true;
        return pr == VK_SUCCESS || pr == VK_SUBOPTIMAL_KHR;
    }

    void Engine::Impl::destroySequenceResources() {
        if (seqPipeline) {
            vkDestroyPipeline(device, seqPipeline, nullptr);
            seqPipeline = VK_NULL_HANDLE;
        }
        if (seqPipelineLayout) {
            vkDestroyPipelineLayout(device, seqPipelineLayout, nullptr);
            seqPipelineLayout = VK_NULL_HANDLE;
        }
        if (seqDescPool) {
            vkDestroyDescriptorPool(device, seqDescPool, nullptr);
            seqDescPool = VK_NULL_HANDLE;
            seqDescSet = VK_NULL_HANDLE;
        }
        if (seqSetLayout) {
            vkDestroyDescriptorSetLayout(device, seqSetLayout, nullptr);
            seqSetLayout = VK_NULL_HANDLE;
        }
        if (seqSampler) {
            vkDestroySampler(device, seqSampler, nullptr);
            seqSampler = VK_NULL_HANDLE;
        }
        if (seqView) {
            vkDestroyImageView(device, seqView, nullptr);
            seqView = VK_NULL_HANDLE;
        }
        if (seqImage) {
            vkDestroyImage(device, seqImage, nullptr);
            seqImage = VK_NULL_HANDLE;
        }
        if (seqMemory) {
            vkFreeMemory(device, seqMemory, nullptr);
            seqMemory = VK_NULL_HANDLE;
        }
        if (seqFragModule) {
            vkDestroyShaderModule(device, seqFragModule, nullptr);
            seqFragModule = VK_NULL_HANDLE;
        }
        seqLayers = 0;
        seqWidth = 0;
        seqHeight = 0;
        seqLayerBytes = 0;
    }

    bool Engine::Impl::setTextureArray(const uint8_t *data, size_t length) {
        if (!device || !shared || !data || length < 104) return false;

        const uint8_t magic[12] = {0xAB, 0x4B, 0x54, 0x58, 0x20, 0x32,
                                   0x30, 0xBB, 0x0D, 0x0A, 0x1A, 0x0A};
        if (std::memcmp(data, magic, sizeof(magic)) != 0) {
            VAP_LOGE("setTextureArray: not a KTX2 file");
            return false;
        }
        auto u32 = [&](size_t offset) -> uint32_t {
            return (uint32_t) data[offset] | ((uint32_t) data[offset + 1] << 8) |
                   ((uint32_t) data[offset + 2] << 16) | ((uint32_t) data[offset + 3] << 24);
        };
        auto u64 = [&](size_t offset) -> uint64_t {
            return (uint64_t) u32(offset) | ((uint64_t) u32(offset + 4) << 32);
        };

        const VkFormat format = static_cast<VkFormat>(u32(12));
        const uint32_t width = u32(20);
        const uint32_t height = u32(24);
        const uint32_t layers = u32(32);
        const uint32_t levels = u32(40);
        const uint32_t supercompression = u32(44);
        if (width == 0 || height == 0 || layers == 0 || levels == 0 ||
            supercompression != 0 || u32(36) != 1) {
            VAP_LOGE("setTextureArray: unsupported KTX2 geometry");
            return false;
        }

        VkPhysicalDeviceFeatures features{};
        vkGetPhysicalDeviceFeatures(physical, &features);
        const bool astc =
                (format >= VK_FORMAT_ASTC_4x4_UNORM_BLOCK &&
                 format <= VK_FORMAT_ASTC_12x12_SRGB_BLOCK);
        const bool etc2 = (format == VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK ||
                           format == VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK ||
                           format == VK_FORMAT_ETC2_R8G8B8_SRGB_BLOCK ||
                           format == VK_FORMAT_ETC2_R8G8B8A8_SRGB_BLOCK);
        if ((astc && !features.textureCompressionASTC_LDR) ||
            (etc2 && !features.textureCompressionETC2) || (!astc && !etc2)) {
            VAP_LOGE("setTextureArray: format %d unsupported", (int) format);
            return false;
        }

        const uint64_t levelOffset = u64(80);
        const uint64_t levelLength = u64(88);
        if (levelOffset > length || levelLength == 0 ||
            levelLength > length - levelOffset || levelLength % layers != 0) {
            VAP_LOGE("setTextureArray: invalid level 0 range");
            return false;
        }

        vkDeviceWaitIdle(device);
        destroySequenceResources();

        VkShaderModuleCreateInfo smci{
                .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .codeSize = vap_vk_shaders::kSeqFragSpv_WORDS * sizeof(uint32_t),
                .pCode = vap_vk_shaders::kSeqFragSpv,
        };
        if (!check(vkCreateShaderModule(device, &smci, nullptr, &seqFragModule),
                   "seqFragModule")) {
            return false;
        }

        VkDescriptorSetLayoutBinding binding{
                .binding = 0,
                .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                .descriptorCount = 1,
                .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
                .pImmutableSamplers = nullptr,
        };
        VkDescriptorSetLayoutCreateInfo dsl{
                .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .bindingCount = 1,
                .pBindings = &binding,
        };
        if (!check(vkCreateDescriptorSetLayout(device, &dsl, nullptr, &seqSetLayout),
                   "seqSetLayout")) {
            destroySequenceResources();
            return false;
        }
        VkDescriptorPoolSize poolSize{
                .type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                .descriptorCount = 1,
        };
        VkDescriptorPoolCreateInfo dpci{
                .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .maxSets = 1,
                .poolSizeCount = 1,
                .pPoolSizes = &poolSize,
        };
        if (!check(vkCreateDescriptorPool(device, &dpci, nullptr, &seqDescPool),
                   "seqDescPool")) {
            destroySequenceResources();
            return false;
        }
        VkDescriptorSetAllocateInfo dsai{
                .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
                .pNext = nullptr,
                .descriptorPool = seqDescPool,
                .descriptorSetCount = 1,
                .pSetLayouts = &seqSetLayout,
        };
        if (!check(vkAllocateDescriptorSets(device, &dsai, &seqDescSet),
                   "seqDescSet")) {
            destroySequenceResources();
            return false;
        }
        VkPushConstantRange pushRange{
                .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
                .offset = 0,
                .size = sizeof(uint32_t),
        };
        VkPipelineLayoutCreateInfo pli{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .setLayoutCount = 1,
                .pSetLayouts = &seqSetLayout,
                .pushConstantRangeCount = 1,
                .pPushConstantRanges = &pushRange,
        };
        if (!check(vkCreatePipelineLayout(device, &pli, nullptr, &seqPipelineLayout),
                   "seqPipelineLayout")) {
            destroySequenceResources();
            return false;
        }

        VkImageCreateInfo ici{
                .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .imageType = VK_IMAGE_TYPE_2D,
                .format = format,
                .extent = {width, height, 1},
                .mipLevels = 1,
                .arrayLayers = layers,
                .samples = VK_SAMPLE_COUNT_1_BIT,
                .tiling = VK_IMAGE_TILING_OPTIMAL,
                .usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
                .queueFamilyIndexCount = 0,
                .pQueueFamilyIndices = nullptr,
                .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        };
        if (!check(vkCreateImage(device, &ici, nullptr, &seqImage), "seqImage")) {
            destroySequenceResources();
            return false;
        }
        VkMemoryRequirements imageReqs{};
        vkGetImageMemoryRequirements(device, seqImage, &imageReqs);
        uint32_t imageType = findMemoryType(physical, imageReqs.memoryTypeBits,
                                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (imageType == UINT32_MAX) {
            VAP_LOGE("setTextureArray: no device-local memory");
            destroySequenceResources();
            return false;
        }
        VkMemoryAllocateInfo imageAi{
                .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                .pNext = nullptr,
                .allocationSize = imageReqs.size,
                .memoryTypeIndex = imageType,
        };
        if (!check(vkAllocateMemory(device, &imageAi, nullptr, &seqMemory), "seqMemory") ||
            !check(vkBindImageMemory(device, seqImage, seqMemory, 0), "bindSeqImage")) {
            destroySequenceResources();
            return false;
        }

        VkImageViewCreateInfo vci{
                .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .image = seqImage,
                .viewType = VK_IMAGE_VIEW_TYPE_2D_ARRAY,
                .format = format,
                .components = {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
                               VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY},
                .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, layers},
        };
        if (!check(vkCreateImageView(device, &vci, nullptr, &seqView), "seqView")) {
            destroySequenceResources();
            return false;
        }
        VkSamplerCreateInfo sci{
                .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .magFilter = VK_FILTER_LINEAR,
                .minFilter = VK_FILTER_LINEAR,
                .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
                .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                .mipLodBias = 0.f,
                .anisotropyEnable = VK_FALSE,
                .maxAnisotropy = 1.f,
                .compareEnable = VK_FALSE,
                .compareOp = VK_COMPARE_OP_ALWAYS,
                .minLod = 0.f,
                .maxLod = 0.f,
                .borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK,
                .unnormalizedCoordinates = VK_FALSE,
        };
        if (!check(vkCreateSampler(device, &sci, nullptr, &seqSampler), "seqSampler")) {
            destroySequenceResources();
            return false;
        }
        VkDescriptorImageInfo dii{
                .sampler = seqSampler,
                .imageView = seqView,
                .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        };
        VkWriteDescriptorSet write{
                .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                .pNext = nullptr,
                .dstSet = seqDescSet,
                .dstBinding = 0,
                .dstArrayElement = 0,
                .descriptorCount = 1,
                .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                .pImageInfo = &dii,
                .pBufferInfo = nullptr,
                .pTexelBufferView = nullptr,
        };
        vkUpdateDescriptorSets(device, 1, &write, 0, nullptr);

        VkBuffer staging = VK_NULL_HANDLE;
        VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
        auto destroyStaging = [&]() {
            if (staging) vkDestroyBuffer(device, staging, nullptr);
            if (stagingMemory) vkFreeMemory(device, stagingMemory, nullptr);
            staging = VK_NULL_HANDLE;
            stagingMemory = VK_NULL_HANDLE;
        };
        VkBufferCreateInfo bci{
                .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .size = levelLength,
                .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
                .queueFamilyIndexCount = 0,
                .pQueueFamilyIndices = nullptr,
        };
        if (!check(vkCreateBuffer(device, &bci, nullptr, &staging), "seqStaging")) {
            destroyStaging();
            destroySequenceResources();
            return false;
        }
        vkGetBufferMemoryRequirements(device, staging, &imageReqs);
        uint32_t stagingType = findMemoryType(physical, imageReqs.memoryTypeBits,
                                              VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                              VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (stagingType == UINT32_MAX) {
            destroyStaging();
            destroySequenceResources();
            return false;
        }
        VkMemoryAllocateInfo stagingAi{
                .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                .pNext = nullptr,
                .allocationSize = imageReqs.size,
                .memoryTypeIndex = stagingType,
        };
        if (!check(vkAllocateMemory(device, &stagingAi, nullptr, &stagingMemory),
                   "seqStagingMemory")) {
            destroyStaging();
            destroySequenceResources();
            return false;
        }
        if (!check(vkBindBufferMemory(device, staging, stagingMemory, 0), "bindSeqStaging")) {
            destroyStaging();
            destroySequenceResources();
            return false;
        }
        void *mapped = nullptr;
        if (!check(vkMapMemory(device, stagingMemory, 0, levelLength, 0, &mapped),
                   "mapSeqStaging")) {
            destroyStaging();
            destroySequenceResources();
            return false;
        }
        std::memcpy(mapped, data + levelOffset, (size_t) levelLength);
        vkUnmapMemory(device, stagingMemory);

        VkCommandBuffer cmd = VK_NULL_HANDLE;
        VkCommandBufferAllocateInfo cai{
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                .pNext = nullptr,
                .commandPool = cmdPool,
                .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                .commandBufferCount = 1,
        };
        if (!check(vkAllocateCommandBuffers(device, &cai, &cmd), "seqUploadCmd")) {
            destroyStaging();
            destroySequenceResources();
            return false;
        }
        VkCommandBufferBeginInfo cbi{
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                .pNext = nullptr,
                .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
                .pInheritanceInfo = nullptr,
        };
        vkBeginCommandBuffer(cmd, &cbi);
        VkImageMemoryBarrier toDst{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = 0,
                .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = seqImage,
                .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, layers},
        };
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                             &toDst);
        VkBufferImageCopy region{
                .bufferOffset = 0,
                .bufferRowLength = 0,
                .bufferImageHeight = 0,
                .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, layers},
                .imageOffset = {0, 0, 0},
                .imageExtent = {width, height, 1},
        };
        vkCmdCopyBufferToImage(cmd, staging, seqImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                               1, &region);
        VkImageMemoryBarrier toSample{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_SHADER_READ_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                .newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = seqImage,
                .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, layers},
        };
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr,
                             1, &toSample);
        vkEndCommandBuffer(cmd);

        VkFence fence = VK_NULL_HANDLE;
        VkFenceCreateInfo fci{
                .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
        };
        if (!check(vkCreateFence(device, &fci, nullptr, &fence), "seqUploadFence")) {
            vkFreeCommandBuffers(device, cmdPool, 1, &cmd);
            destroyStaging();
            destroySequenceResources();
            return false;
        }
        VkSubmitInfo submit{
                .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                .pNext = nullptr,
                .waitSemaphoreCount = 0,
                .pWaitSemaphores = nullptr,
                .pWaitDstStageMask = nullptr,
                .commandBufferCount = 1,
                .pCommandBuffers = &cmd,
                .signalSemaphoreCount = 0,
                .pSignalSemaphores = nullptr,
        };
        VkResult submitResult;
        {
            QueueGuard qlock(shared);
            submitResult = vkQueueSubmit(graphicsQueue, 1, &submit, fence);
        }
        if (submitResult != VK_SUCCESS ||
            vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX) != VK_SUCCESS) {
            VAP_LOGE("setTextureArray: upload submit/wait failed");
            vkDestroyFence(device, fence, nullptr);
            vkFreeCommandBuffers(device, cmdPool, 1, &cmd);
            destroyStaging();
            destroySequenceResources();
            return false;
        }
        vkDestroyFence(device, fence, nullptr);
        vkFreeCommandBuffers(device, cmdPool, 1, &cmd);
        destroyStaging();
        seqLayers = layers;
        seqWidth = width;
        seqHeight = height;
        seqLayerBytes = (uint32_t) (levelLength / layers);
        if (renderPass && !ensureSequencePipeline()) {
            destroySequenceResources();
            return false;
        }
        VAP_LOGI("Texture array ready: %ux%u layers=%u format=%d", width, height, layers,
                 (int) format);
        return true;
    }

    bool Engine::Impl::presentSequence(uint32_t layer) {
        // Hold the maintenance lock for the whole present so clearOutputWindow cannot
        // destroy the swapchain between acquire and submit.
        // 整个 present 期间持有维护锁，避免 clearOutputWindow 在 acquire 与 submit
        // 之间销毁交换链。
        QueueGuard qlock(shared);
        if (!ready() || !seqPipeline || !seqView || seqLayers == 0) return false;
        FrameSlot &slot = frames[frameIndex];
        if (!waitRetireFrame(slot)) return false;
        if (!check(vkResetFences(device, 1, &slot.fence), "resetSeqFence")) return false;

        uint32_t imageIndex = 0;
        VkResult acq = vkAcquireNextImageKHR(device, swapchain, UINT64_MAX, slot.imageAvailable,
                                             VK_NULL_HANDLE, &imageIndex);
        if (acq == VK_ERROR_OUT_OF_DATE_KHR || acq == VK_SUBOPTIMAL_KHR) {
            waitRetireAllFrames();
            createSwapchain((int) swapExtent.width, (int) swapExtent.height);
            return false;
        }
        if (acq != VK_SUCCESS) {
            VAP_LOGE("presentSequence: acquire failed %d", (int) acq);
            return false;
        }
        recordSequenceCommands(slot, imageIndex, layer % seqLayers);
        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit{
                .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                .pNext = nullptr,
                .waitSemaphoreCount = 1,
                .pWaitSemaphores = &slot.imageAvailable,
                .pWaitDstStageMask = &waitStage,
                .commandBufferCount = 1,
                .pCommandBuffers = &slot.cmd,
                .signalSemaphoreCount = 1,
                .pSignalSemaphores = &slot.renderFinished,
        };
        VkResult pr = VK_SUCCESS;
        {
            QueueGuard qlock(shared);
            if (!check(vkQueueSubmit(graphicsQueue, 1, &submit, slot.fence), "seqQueueSubmit")) {
                return false;
            }
            VkPresentInfoKHR present{
                    .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
                    .pNext = nullptr,
                    .waitSemaphoreCount = 1,
                    .pWaitSemaphores = &slot.renderFinished,
                    .swapchainCount = 1,
                    .pSwapchains = &swapchain,
                    .pImageIndices = &imageIndex,
                    .pResults = nullptr,
            };
            pr = vkQueuePresentKHR(graphicsQueue, &present);
        }
        slot.submitted = true;
        frameIndex = (frameIndex + 1) % kFramesInFlight;
        if (pr == VK_ERROR_OUT_OF_DATE_KHR || pr == VK_SUBOPTIMAL_KHR) {
            waitRetireAllFrames();
            createSwapchain((int) swapExtent.width, (int) swapExtent.height);
        }
        return pr == VK_SUCCESS || pr == VK_SUBOPTIMAL_KHR;
    }

    bool Engine::Impl::ensureUploadBuffer(FrameSlot &slot, size_t size) {
        if (slot.uploadCapacity >= size && slot.uploadMapped != nullptr) return true;
        if (slot.uploadBuffer) vkDestroyBuffer(device, slot.uploadBuffer, nullptr);
        if (slot.uploadMemory) vkFreeMemory(device, slot.uploadMemory, nullptr);
        slot.uploadBuffer = VK_NULL_HANDLE;
        slot.uploadMemory = VK_NULL_HANDLE;
        slot.uploadMapped = nullptr;
        slot.uploadCapacity = 0;

        VkBufferCreateInfo bci{
                .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .size = size,
                .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
                .queueFamilyIndexCount = 0,
                .pQueueFamilyIndices = nullptr,
        };
        if (!check(vkCreateBuffer(device, &bci, nullptr, &slot.uploadBuffer),
                   "seqUploadBuffer")) {
            return false;
        }
        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(device, slot.uploadBuffer, &req);
        uint32_t type = findMemoryType(physical, req.memoryTypeBits,
                                       VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                       VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (type == UINT32_MAX) return false;
        VkMemoryAllocateInfo ai{
                .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                .pNext = nullptr,
                .allocationSize = req.size,
                .memoryTypeIndex = type,
        };
        if (!check(vkAllocateMemory(device, &ai, nullptr, &slot.uploadMemory),
                   "seqUploadMemory") ||
            !check(vkBindBufferMemory(device, slot.uploadBuffer, slot.uploadMemory, 0),
                   "bindSeqUpload")) {
            return false;
        }
        if (!check(vkMapMemory(device, slot.uploadMemory, 0, size, 0, &slot.uploadMapped),
                   "mapSeqUpload")) {
            return false;
        }
        slot.uploadCapacity = size;
        return true;
    }

    void Engine::Impl::recordSequenceUploadCommands(FrameSlot &slot, uint32_t imageIndex,
                                                    uint32_t layer) {
        vkResetCommandBuffer(slot.cmd, 0);
        VkCommandBufferBeginInfo begin{
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                .pNext = nullptr,
                .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
                .pInheritanceInfo = nullptr,
        };
        vkBeginCommandBuffer(slot.cmd, &begin);

        VkImageMemoryBarrier toDst{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_SHADER_READ_BIT,
                .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = seqImage,
                .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, layer, 1},
        };
        vkCmdPipelineBarrier(slot.cmd, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                             &toDst);
        VkBufferImageCopy region{
                .bufferOffset = 0,
                .bufferRowLength = 0,
                .bufferImageHeight = 0,
                .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, layer, 1},
                .imageOffset = {0, 0, 0},
                .imageExtent = {seqWidth, seqHeight, 1},
        };
        vkCmdCopyBufferToImage(slot.cmd, slot.uploadBuffer, seqImage,
                               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
        VkImageMemoryBarrier toSample{
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .pNext = nullptr,
                .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_SHADER_READ_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                .newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = seqImage,
                .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, layer, 1},
        };
        vkCmdPipelineBarrier(slot.cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr,
                             1, &toSample);

        VkClearValue clear{.color = {{0.f, 0.f, 0.f, params.hasAlpha ? 0.f : 1.f}}};
        VkRenderPassBeginInfo rpBegin{
                .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
                .pNext = nullptr,
                .renderPass = renderPass,
                .framebuffer = framebuffers[imageIndex],
                .renderArea = {{0, 0}, swapExtent},
                .clearValueCount = 1,
                .pClearValues = &clear,
        };
        vkCmdBeginRenderPass(slot.cmd, &rpBegin, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, seqPipeline);
        VkViewport viewport{0, 0, (float) swapExtent.width, (float) swapExtent.height, 0.f, 1.f};
        VkRect2D scissor{{0, 0}, swapExtent};
        vkCmdSetViewport(slot.cmd, 0, 1, &viewport);
        vkCmdSetScissor(slot.cmd, 0, 1, &scissor);
        VkDeviceSize offset = 0;
        vkCmdBindVertexBuffers(slot.cmd, 0, 1, &vertexBuffer, &offset);
        vkCmdBindDescriptorSets(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, seqPipelineLayout,
                                0, 1, &seqDescSet, 0, nullptr);
        vkCmdPushConstants(slot.cmd, seqPipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT,
                           0, sizeof(layer), &layer);
        vkCmdDraw(slot.cmd, 4, 1, 0, 0);
        vkCmdEndRenderPass(slot.cmd);
        vkEndCommandBuffer(slot.cmd);
    }

    bool Engine::Impl::presentSequenceData(uint32_t layer, const uint8_t *data, size_t length) {
        // Keep the swapchain alive across acquire/record/submit/present; see presentSequence.
        // 在 acquire/录制/提交/呈现全程保住交换链；说明见 presentSequence。
        QueueGuard qlock(shared);
        if (!ready() || !seqPipeline || !seqImage || seqLayers == 0 ||
            layer >= seqLayers || data == nullptr || length != seqLayerBytes) {
            return false;
        }
        FrameSlot &slot = frames[frameIndex];
        if (!waitRetireFrame(slot)) return false;
        if (!ensureUploadBuffer(slot, length)) return false;
        std::memcpy(slot.uploadMapped, data, length);
        if (!check(vkResetFences(device, 1, &slot.fence), "resetSeqDataFence")) return false;

        uint32_t imageIndex = 0;
        VkResult acq = vkAcquireNextImageKHR(device, swapchain, UINT64_MAX, slot.imageAvailable,
                                             VK_NULL_HANDLE, &imageIndex);
        if (acq == VK_ERROR_OUT_OF_DATE_KHR || acq == VK_SUBOPTIMAL_KHR) {
            waitRetireAllFrames();
            createSwapchain((int) swapExtent.width, (int) swapExtent.height);
            return false;
        }
        if (acq != VK_SUCCESS) return false;

        recordSequenceUploadCommands(slot, imageIndex, layer);
        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit{
                .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                .pNext = nullptr,
                .waitSemaphoreCount = 1,
                .pWaitSemaphores = &slot.imageAvailable,
                .pWaitDstStageMask = &waitStage,
                .commandBufferCount = 1,
                .pCommandBuffers = &slot.cmd,
                .signalSemaphoreCount = 1,
                .pSignalSemaphores = &slot.renderFinished,
        };
        VkResult pr = VK_SUCCESS;
        {
            QueueGuard qlock(shared);
            if (!check(vkQueueSubmit(graphicsQueue, 1, &submit, slot.fence),
                       "seqDataQueueSubmit")) {
                return false;
            }
            VkPresentInfoKHR present{
                    .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
                    .pNext = nullptr,
                    .waitSemaphoreCount = 1,
                    .pWaitSemaphores = &slot.renderFinished,
                    .swapchainCount = 1,
                    .pSwapchains = &swapchain,
                    .pImageIndices = &imageIndex,
                    .pResults = nullptr,
            };
            pr = vkQueuePresentKHR(graphicsQueue, &present);
        }
        slot.submitted = true;
        frameIndex = (frameIndex + 1) % kFramesInFlight;
        if (pr == VK_ERROR_OUT_OF_DATE_KHR || pr == VK_SUBOPTIMAL_KHR) {
            waitRetireAllFrames();
            createSwapchain((int) swapExtent.width, (int) swapExtent.height);
        }
        return pr == VK_SUCCESS || pr == VK_SUBOPTIMAL_KHR;
    }

    bool Engine::Impl::presentAhb(AHardwareBuffer *buffer, uint64_t bufferId) {
        // Keep teardown from destroying the swapchain while this frame is in flight.
        // 防止帧在途时 teardown 销毁交换链。
        QueueGuard qlock(shared);
        if (!ready() || !buffer) return false;
        if (!shared || !shared->fnGetAhbProps) {
            VAP_LOGE("presentAhb: AHB entry points missing");
            return false;
        }

        VkAndroidHardwareBufferFormatPropertiesANDROID fmtProps{};
        VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
        if (!resolveAhbProps(buffer, fmtProps, ahbProps)) return false;
        const bool externalFormat = (fmtProps.format == VK_FORMAT_UNDEFINED);
        if (!ensureYcbcrResources(fmtProps, externalFormat) || !pipeline ||
            !frameDescSetsReady) {
            VAP_LOGE("presentAhb: ycbcr/pipeline not ready");
            return false;
        }

        FrameSlot &slot = frames[frameIndex];
        // Wait only for this slot's prior submission, preserving a two-frame pipeline instead of
        // serializing every presentation.
        // 仅等待当前槽位的上一次提交，保留双帧流水而非串行化每次呈现。
        if (!waitRetireFrame(slot)) return false;
        if (!check(vkResetFences(device, 1, &slot.fence), "resetFrameFence")) return false;

        if (!acquireAhbImport(buffer, bufferId, fmtProps, ahbProps, externalFormat, slot.import)) {
            return false;
        }
        bindSlotImport(slot, slot.import);

        uint32_t imageIndex = 0;
        VkResult acq = vkAcquireNextImageKHR(device, swapchain, UINT64_MAX, slot.imageAvailable,
                                             VK_NULL_HANDLE, &imageIndex);
        if (acq == VK_ERROR_OUT_OF_DATE_KHR || acq == VK_SUBOPTIMAL_KHR) {
            destroyAhbImport(slot.import);
            slot.boundView = VK_NULL_HANDLE;
            slot.boundAhb = nullptr;
            waitRetireAllFrames();
            createSwapchain((int) swapExtent.width, (int) swapExtent.height);
            return false;
        }
        if (acq != VK_SUCCESS) {
            VAP_LOGE("acquire failed %d", (int) acq);
            destroyAhbImport(slot.import);
            slot.boundView = VK_NULL_HANDLE;
            slot.boundAhb = nullptr;
            return false;
        }

        recordDrawCommands(slot, slot.import.image, slot.import.view, imageIndex);

        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit{
                .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                .pNext = nullptr,
                .waitSemaphoreCount = 1,
                .pWaitSemaphores = &slot.imageAvailable,
                .pWaitDstStageMask = &waitStage,
                .commandBufferCount = 1,
                .pCommandBuffers = &slot.cmd,
                .signalSemaphoreCount = 1,
                .pSignalSemaphores = &slot.renderFinished,
        };
        VkResult pr = VK_SUCCESS;
        {
            QueueGuard qlock(shared);
            if (!check(vkQueueSubmit(graphicsQueue, 1, &submit, slot.fence), "queueSubmit")) {
                destroyAhbImport(slot.import);
                slot.boundView = VK_NULL_HANDLE;
                slot.boundAhb = nullptr;
                return false;
            }
            VkPresentInfoKHR present{
                    .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
                    .pNext = nullptr,
                    .waitSemaphoreCount = 1,
                    .pWaitSemaphores = &slot.renderFinished,
                    .swapchainCount = 1,
                    .pSwapchains = &swapchain,
                    .pImageIndices = &imageIndex,
                    .pResults = nullptr,
            };
            pr = vkQueuePresentKHR(graphicsQueue, &present);
        }
        slot.submitted = true;
        frameIndex = (frameIndex + 1) % kFramesInFlight;

        if (pr == VK_ERROR_OUT_OF_DATE_KHR || pr == VK_SUBOPTIMAL_KHR) {
            waitRetireAllFrames();
            createSwapchain((int) swapExtent.width, (int) swapExtent.height);
        }
        return pr == VK_SUCCESS || pr == VK_SUBOPTIMAL_KHR;
    }

    PrepareResult Engine::Impl::prepareAhb(AHardwareBuffer *buffer, uint64_t bufferId) {
        if (swapchainInvalid) return PrepareResult::Recreate;
        if (!ready() || !buffer) return PrepareResult::Failed;
        if (!shared || !shared->fnGetAhbProps) {
            VAP_LOGE("prepareAhb: AHB entry points missing");
            return PrepareResult::Failed;
        }

        VkAndroidHardwareBufferFormatPropertiesANDROID fmtProps{};
        VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
        if (!resolveAhbProps(buffer, fmtProps, ahbProps)) return PrepareResult::Failed;
        const bool externalFormat = (fmtProps.format == VK_FORMAT_UNDEFINED);
        if (cachedExternalFormat != (externalFormat ? fmtProps.externalFormat : 0) || cachedAhbFormat != fmtProps.format) {
            for (const auto &frame : frames) {
                VkFence completion = frame.batch ? frame.batch->fence : frame.fence;
                if ((frame.batch || frame.submitted) && vkGetFenceStatus(device, completion) == VK_NOT_READY)
                    return PrepareResult::NotReady;
            }
        }
        if (!ensureYcbcrResources(fmtProps, externalFormat) || !pipeline ||
            !frameDescSetsReady) {
            VAP_LOGE("prepareAhb: ycbcr/pipeline not ready");
            return PrepareResult::Failed;
        }

        FrameSlot &slot = frames[frameIndex];
        if (slot.prepared) return PrepareResult::Prepared;
        VkFence completion = slot.batch ? slot.batch->fence : slot.fence;
        if ((slot.batch || slot.submitted) && vkGetFenceStatus(device, completion) == VK_NOT_READY)
            return PrepareResult::NotReady;
        if (!waitRetireFrame(slot)) return PrepareResult::Failed;

        if (!acquireAhbImport(buffer, bufferId, fmtProps, ahbProps, externalFormat, slot.import)) {
            return PrepareResult::Failed;
        }
        bindSlotImport(slot, slot.import);

        // No display wait on the shared render thread. Keep the pending frame on NotReady.
        uint32_t imageIndex = 0;
        VkResult acq = vkAcquireNextImageKHR(device, swapchain, 0, slot.imageAvailable, VK_NULL_HANDLE, &imageIndex);
        if (acq == VK_NOT_READY || acq == VK_TIMEOUT) {
            recycleAhbImport(slot.import);
            slot.boundView = VK_NULL_HANDLE;
            slot.boundAhb = nullptr;
            return PrepareResult::NotReady;
        }
        if (acq == VK_ERROR_OUT_OF_DATE_KHR) {
            recycleAhbImport(slot.import);
            return PrepareResult::Recreate;
        }
        if (acq != VK_SUCCESS && acq != VK_SUBOPTIMAL_KHR) {
            destroyAhbImport(slot.import);
            return PrepareResult::Failed;
        }
        slot.acquireSignaled = true;

        recordDrawCommands(slot, slot.import.image, slot.import.view, imageIndex);
        slot.preparedImageIndex = imageIndex;
        slot.prepared = true;
        return PrepareResult::Prepared;
    }

    void Engine::Impl::cancelPrepared() {
        drainAcquireSemaphores();
        for (auto &frame: frames) {
            if (frame.prepared) {
                recycleAhbImport(frame.import);
                frame.boundView = VK_NULL_HANDLE;
                frame.boundAhb = nullptr;
            }
            frame.prepared = false;
        }
    }

    void Engine::Impl::retireSignaledBatches() {
        for (auto &frame: frames) {
            if (!frame.batch) continue;
            if (vkGetFenceStatus(device, frame.batch->fence) != VK_SUCCESS) continue;
            frame.batch->holds--;
            frame.batch = nullptr;
            frame.submitted = false;
            recycleAhbImport(frame.import);
            frame.boundView = VK_NULL_HANDLE;
            frame.boundAhb = nullptr;
        }
    }


    void Engine::Impl::destroy() {
        {
            QueueGuard maintenance(shared);
            if (device) vkDeviceWaitIdle(device);
            cancelPrepared();
            waitRetireAllFrames();
            // Device-idle guarantees no view/image/memory or acquired AHB reference remains in use.
            // 设备空闲保证视图、图像、内存及已获取的 AHB 引用均不再被使用。
            destroySwapchain();
            destroyYcbcrResources();
            destroyOpeningResources();
            destroySequenceResources();
            if (surface) {
                vkDestroySurfaceKHR(instance, surface, nullptr);
                surface = VK_NULL_HANDLE;
            }
            if (window) {
                ANativeWindow_release(window);
                window = nullptr;
            }
            if (vertexBuffer) vkDestroyBuffer(device, vertexBuffer, nullptr);
            if (vertexMemory) vkFreeMemory(device, vertexMemory, nullptr);
            if (descPool) vkDestroyDescriptorPool(device, descPool, nullptr);
            for (auto &frame: frames) {
                if (frame.imageAvailable) vkDestroySemaphore(device, frame.imageAvailable, nullptr);
                if (frame.renderFinished) vkDestroySemaphore(device, frame.renderFinished, nullptr);
                if (frame.fence) vkDestroyFence(device, frame.fence, nullptr);
                if (frame.uploadBuffer) vkDestroyBuffer(device, frame.uploadBuffer, nullptr);
                if (frame.uploadMemory) vkFreeMemory(device, frame.uploadMemory, nullptr);
                frame = {};
            }
            if (acquireFence) {
                vkDestroyFence(device, acquireFence, nullptr);
                acquireFence = VK_NULL_HANDLE;
            }
            if (cmdPool) vkDestroyCommandPool(device, cmdPool, nullptr);
        }
        // Device, shader modules, and instance are process-wide; this engine only drops its ref.
        // 设备、Shader 模块与实例均为进程级对象；当前引擎仅减少共享引用。
        if (shared) {
            SharedDevice::release();
            shared = nullptr;
        }
        device = VK_NULL_HANDLE;
        instance = VK_NULL_HANDLE;
        physical = VK_NULL_HANDLE;
        graphicsQueue = VK_NULL_HANDLE;
        cmdPool = VK_NULL_HANDLE;
        frameIndex = 0;
        frameDescSetsReady = false;
        vertModule = VK_NULL_HANDLE;
        fragModule = VK_NULL_HANDLE;
        pipelineLayout = VK_NULL_HANDLE;
        setLayout = VK_NULL_HANDLE;
        descPool = VK_NULL_HANDLE;
        vertexBuffer = VK_NULL_HANDLE;
        vertexMemory = VK_NULL_HANDLE;
    }

    Engine *Engine::create(const CreateParams &params) {
        auto *engine = new Engine();
        engine->impl_ = new Impl();
        engine->impl_->params = params;
        if (!engine->impl_->bindShared() || !engine->impl_->createCommandResources() ||
            !engine->impl_->createShadersAndPipelineBase() ||
            !engine->impl_->createVertexBuffer()) {
            engine->destroy();
            return nullptr;
        }
        VAP_LOGI("Vulkan 1.1 engine ready (shared device) video=%dx%d", params.videoW,
                 params.videoH);
        return engine;
    }


    void Engine::destroy() {
        if (!impl_) {
            delete this;
            return;
        }
        impl_->destroy();
        delete impl_;
        impl_ = nullptr;
        delete this;
    }

    bool Engine::setOutputWindow(ANativeWindow *window, int width, int height) {
        if (!impl_ || !window) return false;
        QueueGuard maintenance(impl_->shared);
        clearOutputWindow();
        ANativeWindow_acquire(window);
        impl_->window = window;
        VkAndroidSurfaceCreateInfoKHR sci{
                .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
                .pNext = nullptr,
                .flags = 0,
                .window = window,
        };
        if (!check(vkCreateAndroidSurfaceKHR(impl_->instance, &sci, nullptr, &impl_->surface),
                   "androidSurface")) {
            ANativeWindow_release(window);
            impl_->window = nullptr;
            return false;
        }
        VkBool32 supported = VK_FALSE;
        vkGetPhysicalDeviceSurfaceSupportKHR(impl_->physical, impl_->graphicsQueueFamily,
                                             impl_->surface,
                                             &supported);
        if (!supported) {
            VAP_LOGE("Queue family does not support present");
            clearOutputWindow();
            return false;
        }
        if (!impl_->createSwapchain(width, height)) return false;
        // hasAlpha only: opaque clips keep clear-alpha=1 and do not write A, so a
        // transparent present would stay see-through after the first decoded frame.
        // 仅 hasAlpha：不透明素材 clear 的 alpha=1 且不写 A，透明 present 会让后续解码帧一直透底。
        // When an opening texture is already uploaded, the first present is the RGBA opening
        // frame; skip the transparent clear so FIFO does not show a one-vsync hole first.
        // 已上传 opening 纹理时，首次 present 就是 RGBA 首帧；跳过透明 clear，避免 FIFO
        // 先显示一帧透明洞。
        if (impl_->params.hasAlpha && impl_->openingView == VK_NULL_HANDLE) {
            impl_->presentClear();
        }
        return true;
    }

    void Engine::clearOutputWindow() {
        if (!impl_) return;
        QueueGuard maintenance(impl_->shared);
        if (!impl_->swapchain && !impl_->surface && !impl_->window) return;
        if (impl_->device) {
            {
                QueueGuard qlock(impl_->shared);
                vkDeviceWaitIdle(impl_->device);
            }
            impl_->cancelPrepared();
            impl_->waitRetireAllFrames();
        }
        impl_->destroySwapchain();
        if (impl_->surface) {
            vkDestroySurfaceKHR(impl_->instance, impl_->surface, nullptr);
            impl_->surface = VK_NULL_HANDLE;
        }
        if (impl_->window) {
            ANativeWindow_release(impl_->window);
            impl_->window = nullptr;
        }
    }

    bool Engine::setOpeningPixels(const uint32_t *pixels, int width, int height) {
        Impl *i = pimpl();
        if (!i) return false;
        QueueGuard maintenance(i->shared);
        return i->createOpeningResources(pixels, width, height);
    }

    PrepareResult Engine::presentOpening() {
        Impl *i = pimpl();
        if (!i || !i->shared) return PrepareResult::Failed;
        TryQueueGuard render(i->shared);
        if (!render) return PrepareResult::NotReady;
        if (i->swapchainInvalid) return PrepareResult::Recreate;
        if (i->presentOpening()) return PrepareResult::Prepared;
        return i->swapchainInvalid ? PrepareResult::Recreate : PrepareResult::NotReady;
    }

    bool Engine::setTextureArray(const uint8_t *data, size_t length) {
        Impl *i = pimpl();
        if (!i || !i->shared) return false;
        QueueGuard maintenance(i->shared);
        return i->setTextureArray(data, length);
    }

    bool Engine::presentSequenceFrame(uint32_t layer) {
        Impl *i = pimpl();
        return i && i->presentSequence(layer);
    }

    bool Engine::presentSequenceFrameData(uint32_t layer, const uint8_t *data, size_t length) {
        Impl *i = pimpl();
        return i && i->presentSequenceData(layer, data, length);
    }

    bool Engine::presentHardwareBuffer(AHardwareBuffer *buffer, uint64_t bufferId) {
        Impl *i = pimpl();
        return i && i->presentAhb(buffer, bufferId);
    }

    PrepareResult Engine::preparePresentHardwareBuffer(AHardwareBuffer *buffer, uint64_t bufferId) {
        Impl *i = pimpl();
        if (!i || !i->shared) return PrepareResult::Failed;
        TryQueueGuard render(i->shared);
        return render ? i->prepareAhb(buffer, bufferId) : PrepareResult::NotReady;
    }

    void Engine::resetImportPool() {
        if (!impl_) return;
        QueueGuard maintenance(impl_->shared);
        impl_->importPool.resetGeneration([this](AhbImport &value) { impl_->destroyAhbImport(value); });
    }

    void Engine::releaseOpeningResources() {
        if (!impl_) return;
        QueueGuard maintenance(impl_->shared);
        // The opening image may still be sampled by a submitted frame slot. Retire all
        // slots before freeing it so the GPU never reads freed memory.
        // opening 纹理可能仍被已提交的帧槽位采样；释放前先回收所有槽位，
        // 避免 GPU 读取已释放内存。
        impl_->waitRetireAllFrames();
        impl_->destroyOpeningResources();
    }

    void Engine::cancelPreparedPresent() {
        Impl *i = pimpl();
        if (!i) return;
        QueueGuard maintenance(i->shared);
        i->cancelPrepared();
    }

    void Engine::retireSignaledBatches() {
        Impl *i = pimpl();
        if (!i || !i->shared) return;
        TryQueueGuard render(i->shared);
        if (render) {
            i->retireOpeningUpload(false);
            i->retireSignaledBatches();
        }
    }

    bool Engine::submitPreparedBatch(Engine **engines, int count) {
        if (!engines || count <= 0) return true;
        if (!engines[0] || !engines[0]->impl_) return false;
        TryQueueGuard render(engines[0]->impl_->shared);
        if (!render) return false; // Prepared slots remain available for the next tick.
        constexpr int kMaxBatch = 16;
        Impl *impls[kMaxBatch];
        FrameSlot *slots[kMaxBatch];
        VkCommandBuffer cmds[kMaxBatch];
        VkSwapchainKHR swaps[kMaxBatch];
        uint32_t imageIndices[kMaxBatch];
        int m = 0;
        SharedDevice *shared = nullptr;
        for (int i = 0; i < count; i++) {
            Engine *e = engines[i];
            if (!e || !e->impl_) continue;
            Impl *im = e->impl_;
            FrameSlot &slot = im->frames[im->frameIndex];
            if (!slot.prepared) continue;
            if (m >= kMaxBatch) {
                continue; // Caller batches at most 16; surplus stays prepared for a later tick.
            }
            impls[m] = im;
            slots[m] = &slot;
            cmds[m] = slot.cmd;
            swaps[m] = im->swapchain;
            imageIndices[m] = slot.preparedImageIndex;
            shared = im->shared;
            m++;
        }
        if (m == 0 || !shared) return true;

        // Select a batch slot with no dependent frames. holds drains as each participant retires;
        // six slots absorb differing skip patterns without reusing an in-flight fence.
        // 选择无依赖帧的批次槽位。每个参与者回收时 holds 递减；六个槽位可吸收不同的
        // 跳帧模式，避免复用仍在途的栅栏。
        SharedDevice::BatchSlot *batch = nullptr;
        for (auto &cand: shared->batchSlots) {
            if (cand.holds != 0) continue;
            if (vkGetFenceStatus(shared->device, cand.fence) != VK_SUCCESS) continue;
            if (!check(vkResetFences(shared->device, 1, &cand.fence), "resetBatchSlot")) {
                for (int j = 0; j < m; j++) {
                    impls[j]->drainAcquireSemaphores();
                    slots[j]->prepared = false;
                }
                return false;
            }
            batch = &cand;
            break;
        }
        if (!batch) return false; // Keep prepared slots; retry after a completion fence signals.

        // Each session acquired with its own imageAvailable; wait them all in one submit so
        // the CPU thread is not parked on vsync while ImageReader / other prepares run.
        // 每路用自己的 imageAvailable 获取；在一次提交里全部等待，避免 CPU 线程
        // 为 vsync 停住，从而挡住 ImageReader 和其他 prepare。
        VkSemaphore acquireWaits[kMaxBatch];
        VkPipelineStageFlags acquireStages[kMaxBatch];
        uint32_t acquireWaitCount = 0;
        for (int j = 0; j < m; j++) {
            if (!slots[j]->acquireSignaled) continue;
            acquireWaits[acquireWaitCount] = slots[j]->imageAvailable;
            acquireStages[acquireWaitCount] = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            acquireWaitCount++;
        }
        VkSubmitInfo submit{
                .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                .pNext = nullptr,
                .waitSemaphoreCount = acquireWaitCount,
                .pWaitSemaphores = acquireWaitCount ? acquireWaits : nullptr,
                .pWaitDstStageMask = acquireWaitCount ? acquireStages : nullptr,
                .commandBufferCount = (uint32_t) m,
                .pCommandBuffers = cmds,
                .signalSemaphoreCount = 1,
                .pSignalSemaphores = &batch->renderFinished,
        };
        VkResult results[kMaxBatch]{};
        bool ok = true;
        bool submitted = false;
        {
            QueueGuard qlock(shared);
            if (!check(vkQueueSubmit(shared->graphicsQueue, 1, &submit, batch->fence),
                       "batchQueueSubmit")) {
                ok = false;
            } else {
                submitted = true;
                VkPresentInfoKHR present{
                        .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
                        .pNext = nullptr,
                        .waitSemaphoreCount = 1,
                        .pWaitSemaphores = &batch->renderFinished,
                        .swapchainCount = (uint32_t) m,
                        .pSwapchains = swaps,
                        .pImageIndices = imageIndices,
                        .pResults = results,
                };
                VkResult pr = vkQueuePresentKHR(shared->graphicsQueue, &present);
                for (int j = 0; j < m; ++j) {
                    if (pr == VK_ERROR_OUT_OF_DATE_KHR || results[j] == VK_ERROR_OUT_OF_DATE_KHR) {
                        impls[j]->swapchainInvalid = true;
                        ok = false;
                    }
                }
                if (pr != VK_SUCCESS && pr != VK_SUBOPTIMAL_KHR && pr != VK_ERROR_OUT_OF_DATE_KHR) {
                    VAP_LOGE("batchPresent failed %d", (int) pr);
                    ok = false;
                }
            }
        }
        if (!submitted) {
            for (int j = 0; j < m; j++) {
                impls[j]->drainAcquireSemaphores();
                slots[j]->prepared = false;
            }
            return false;
        }
        for (int j = 0; j < m; j++) {
            slots[j]->acquireSignaled = false;
            // An out-of-date swapchain is rebuilt by maintenance on the next acquire.
            slots[j]->prepared = false;
            slots[j]->submitted = true;
            slots[j]->batch = batch;
            batch->holds++;
            impls[j]->frameIndex = (impls[j]->frameIndex + 1) % kFramesInFlight;
        }
        return ok;
    }

    bool Engine::ready() const {
        const Impl *i = pimpl();
        return i && i->device && i->swapchain;
    }

    bool Engine::Impl::ready() const {
        return device != VK_NULL_HANDLE && swapchain != VK_NULL_HANDLE;
    }

} // namespace vap_vk

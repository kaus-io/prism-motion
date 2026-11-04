#include "ktx_vk_engine.h"

#include "ktx_queue_guard.h"
#include "ktx_output_binding.h"
#include "ktx_vk_shared_device.h"
#include "ktx_frame_read.h"

#include <android/log.h>
#include <vulkan/vulkan.h>
#include <zstd.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cstring>
#include <vector>

#define KTX_VK_LOG_TAG "KtxAnimVk"
#define KTX_LOGI(...) __android_log_print(ANDROID_LOG_INFO, KTX_VK_LOG_TAG, __VA_ARGS__)
#define KTX_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, KTX_VK_LOG_TAG, __VA_ARGS__)

namespace kap_vk {
    namespace {
        constexpr uint32_t kFramesInFlight = 3;
        constexpr uint32_t kColorFrag[] =
#include "ktx_color_frag.inc"
        ;
        struct ColorPush {
            uint32_t layer = 0;
            uint32_t padding[3]{};
            float rows[12]{};
        };
        static_assert(sizeof(ColorPush) == 64);
        // Bound acquire so a lost / occluded Surface cannot hold the queue lock forever.
        // 限制 acquire 等待，避免丢失 / 被遮挡的 Surface 永久占住队列锁。
        // Never hold the shared queue lock waiting for a compositor buffer.
        constexpr uint64_t kAcquireTimeoutNanos = 0;
        constexpr uint32_t kVertexFloats = 6;
        constexpr uint32_t kVertexStride = kVertexFloats * sizeof(float);
        constexpr uint32_t kVertexOffsetUvAlpha = 2 * sizeof(float);
        constexpr uint32_t kVertexOffsetUvRgb = 4 * sizeof(float);

        bool check(VkResult result, const char *what) {
            if (result == VK_SUCCESS) return true;
            KTX_LOGE("%s failed: %d", what, (int) result);
            return false;
        }

        uint32_t findMemoryType(
                const VkPhysicalDeviceMemoryProperties &props,
                uint32_t typeBits,
                VkMemoryPropertyFlags required) {
            for (uint32_t index = 0; index < props.memoryTypeCount; index++) {
                if ((typeBits & (1u << index)) == 0) continue;
                if ((props.memoryTypes[index].propertyFlags & required) == required) {
                    return index;
                }
            }
            return UINT32_MAX;
        }

        uint32_t readU32(const uint8_t *data, size_t offset) {
            return (uint32_t) data[offset] | ((uint32_t) data[offset + 1] << 8) |
                   ((uint32_t) data[offset + 2] << 16) | ((uint32_t) data[offset + 3] << 24);
        }

        uint64_t readU64(const uint8_t *data, size_t offset) {
            return (uint64_t) readU32(data, offset) |
                   ((uint64_t) readU32(data, offset + 4) << 32);
        }

        struct KtxHeader {
            VkFormat format = VK_FORMAT_UNDEFINED;
            uint32_t width = 0;
            uint32_t height = 0;
            uint32_t layers = 0;
            uint64_t levelOffset = 0;
            uint64_t levelLength = 0;
        };

        bool parseKtx2(const uint8_t *data, size_t length, KtxHeader &out) {
            static constexpr uint8_t kMagic[12] = {
                    0xAB, 0x4B, 0x54, 0x58, 0x20, 0x32,
                    0x30, 0xBB, 0x0D, 0x0A, 0x1A, 0x0A,
            };
            if (length < 104 || std::memcmp(data, kMagic, sizeof(kMagic)) != 0) {
                KTX_LOGE("not a KTX2 file");
                return false;
            }
            const uint32_t vkFormat = readU32(data, 12);
            const uint32_t width = readU32(data, 20);
            const uint32_t height = readU32(data, 24);
            const uint32_t depth = readU32(data, 28);
            const uint32_t layers = readU32(data, 32);
            const uint32_t faces = readU32(data, 36);
            const uint32_t levels = readU32(data, 40);
            const uint32_t supercompression = readU32(data, 44);
            const uint64_t levelOffset = readU64(data, 80);
            const uint64_t levelLength = readU64(data, 88);
            const uint64_t uncompressedLength = readU64(data, 96);
            if (width == 0 || height == 0 || layers == 0 || faces != 1 || levels != 1 ||
                (depth != 0 && depth != 1) || supercompression != 0 ||
                levelLength == 0 || levelLength % layers != 0 ||
                (uncompressedLength != 0 && uncompressedLength != levelLength) ||
                levelOffset < 104 || levelOffset + levelLength > length) {
                KTX_LOGE("unsupported KTX2 geometry/format");
                return false;
            }
            out.format = static_cast<VkFormat>(vkFormat);
            out.width = width;
            out.height = height;
            out.layers = layers;
            out.levelOffset = levelOffset;
            out.levelLength = levelLength;
            return true;
        }

        bool formatSupported(VkFormat format, const SharedDevice &shared) {
            switch (format) {
                case VK_FORMAT_ASTC_4x4_UNORM_BLOCK:
                case VK_FORMAT_ASTC_6x6_UNORM_BLOCK:
                case VK_FORMAT_ASTC_8x8_UNORM_BLOCK:
                case VK_FORMAT_ASTC_10x10_UNORM_BLOCK:
                case VK_FORMAT_ASTC_12x12_UNORM_BLOCK:
                    return shared.astcSupported;
                case VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK:
                    return shared.etc2Supported;
                case VK_FORMAT_R8G8B8A8_UNORM:
                    return true;
                default:
                    return false;
            }
        }

        uint64_t layerBytesForFormat(VkFormat format, uint32_t width, uint32_t height) {
            uint32_t blockWidth = 1;
            uint32_t blockHeight = 1;
            uint32_t blockBytes = 4;
            switch (format) {
                case VK_FORMAT_ASTC_4x4_UNORM_BLOCK:
                    blockWidth = blockHeight = 4;
                    blockBytes = 16;
                    break;
                case VK_FORMAT_ASTC_6x6_UNORM_BLOCK:
                    blockWidth = blockHeight = 6;
                    blockBytes = 16;
                    break;
                case VK_FORMAT_ASTC_8x8_UNORM_BLOCK:
                    blockWidth = blockHeight = 8;
                    blockBytes = 16;
                    break;
                case VK_FORMAT_ASTC_10x10_UNORM_BLOCK:
                    blockWidth = blockHeight = 10;
                    blockBytes = 16;
                    break;
                case VK_FORMAT_ASTC_12x12_UNORM_BLOCK:
                    blockWidth = blockHeight = 12;
                    blockBytes = 16;
                    break;
                case VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK:
                    blockWidth = blockHeight = 4;
                    blockBytes = 16;
                    break;
                case VK_FORMAT_R8G8B8A8_UNORM:
                    return (uint64_t) width * height * 4;
                default:
                    return 0;
            }
            const uint64_t blocksX = (width + blockWidth - 1) / blockWidth;
            const uint64_t blocksY = (height + blockHeight - 1) / blockHeight;
            return blocksX * blocksY * blockBytes;
        }
    } // namespace

    struct Engine::Impl {
        struct FrameSlot {
            VkCommandBuffer cmd = VK_NULL_HANDLE;
            VkSemaphore imageAvailable = VK_NULL_HANDLE;
            VkSemaphore renderFinished = VK_NULL_HANDLE;
            VkFence fence = VK_NULL_HANDLE;
            bool submitted = false;

            VkBuffer uploadBuffer = VK_NULL_HANDLE;
            VkDeviceMemory uploadMemory = VK_NULL_HANDLE;
            void *uploadMapped = nullptr;
            size_t uploadCapacity = 0;
        };

        SharedDevice *shared = nullptr;
        bool narrowTeardown = false;
        bool colorEnabled = false;
        ColorPush colorPush{};
        VkShaderModule colorModule = VK_NULL_HANDLE;
        VkSurfaceKHR surface = VK_NULL_HANDLE;
        ANativeWindow *window = nullptr;
        OutputBinding outputBinding;
        VkSwapchainKHR swapchain = VK_NULL_HANDLE;
        VkFormat swapFormat = VK_FORMAT_UNDEFINED;
        VkExtent2D swapExtent{0, 0};
        std::vector<VkImage> swapImages;
        std::vector<VkImageView> swapViews;
        std::vector<VkFramebuffer> framebuffers;
        VkRenderPass renderPass = VK_NULL_HANDLE;
        VkCommandPool cmdPool = VK_NULL_HANDLE;
        std::array<FrameSlot, kFramesInFlight> frames{};
        uint32_t frameIndex = 0;

        VkPipelineLayout seqPipelineLayout = VK_NULL_HANDLE;
        VkPipeline seqPipeline = VK_NULL_HANDLE;
        VkDescriptorSetLayout seqSetLayout = VK_NULL_HANDLE;
        VkDescriptorPool seqDescPool = VK_NULL_HANDLE;
        VkDescriptorSet seqDescSet = VK_NULL_HANDLE;
        VkSampler seqSampler = VK_NULL_HANDLE;
        VkImageView seqView = VK_NULL_HANDLE;
        VkImage seqImage = VK_NULL_HANDLE;
        VkDeviceMemory seqMemory = VK_NULL_HANDLE;
        uint32_t seqLayers = 0;
        uint32_t seqWidth = 0;
        uint32_t seqHeight = 0;
        uint32_t seqLayerBytes = 0;

        VkBuffer vertexBuffer = VK_NULL_HANDLE;
        VkDeviceMemory vertexMemory = VK_NULL_HANDLE;

        bool initialize();

        void destroy();

        void clearOutputWindowLocked();

        bool createCommandResources();

        bool createVertexBuffer();

        bool ensureRenderPass(VkFormat format);

        bool createSwapchain(int width, int height);

        void destroySwapchain();

        bool ensurePipeline();

        bool createGraphicsPipeline();
        void pushLayer(VkCommandBuffer command, uint32_t layer);

        bool setTextureArray(const uint8_t *data, size_t length);

        bool setTextureArrayRaw(
                VkFormat format,
                uint32_t width,
                uint32_t height,
                uint32_t layers,
                const uint8_t *data,
                size_t length, int fd = -1, int64_t offset = 0);

        bool setTextureArrayZstd(
                VkFormat format,
                uint32_t width,
                uint32_t height,
                uint32_t layers,
                const uint8_t *compressed,
                size_t compressedLength,
                size_t uncompressedLength);

        bool present(uint32_t layer);

        bool presentFile(uint32_t layer, int fd, int64_t offset, size_t length, int *ioError, const void *bytes = nullptr);

        bool ensureUploadBuffer(FrameSlot &slot, size_t size);

        void destroyUploadBuffer(FrameSlot &slot);

        void destroySequenceResources();

        bool waitRetireFrame(FrameSlot &slot, uint64_t timeout = UINT64_MAX);

        bool waitRetireAllFrames(uint64_t timeout = UINT64_MAX);

        bool readyInternal() const;

        void recordRenderCommands(FrameSlot &slot, uint32_t imageIndex, uint32_t layer);

        void recordUploadAndRenderCommands(
                FrameSlot &slot,
                uint32_t imageIndex,
                uint32_t layer);

        bool submitAndPresent(FrameSlot &slot, uint32_t imageIndex);
    };

    bool Engine::Impl::initialize() {
        shared = SharedDevice::acquire();
        if (!shared) return false;
        SharedDevice::consumePrewarm();
        if (!createCommandResources()) return false;
        if (!createVertexBuffer()) return false;
        return true;
    }

    bool Engine::Impl::createCommandResources() {
        VkCommandPoolCreateInfo poolInfo{
                .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                .pNext = nullptr,
                .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
                .queueFamilyIndex = shared->graphicsQueueFamily,
        };
        if (!check(vkCreateCommandPool(shared->device, &poolInfo, nullptr, &cmdPool),
                   "commandPool")) {
            return false;
        }
        VkCommandBufferAllocateInfo allocInfo{
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                .pNext = nullptr,
                .commandPool = cmdPool,
                .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                .commandBufferCount = kFramesInFlight,
        };
        std::array<VkCommandBuffer, kFramesInFlight> buffers{};
        if (!check(vkAllocateCommandBuffers(shared->device, &allocInfo, buffers.data()),
                   "commandBuffers")) {
            return false;
        }
        VkSemaphoreCreateInfo semaphoreInfo{
                .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
        };
        VkFenceCreateInfo fenceInfo{
                .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
        };
        for (uint32_t index = 0; index < kFramesInFlight; index++) {
            auto &slot = frames[index];
            slot.cmd = buffers[index];
            if (!check(vkCreateSemaphore(shared->device, &semaphoreInfo, nullptr,
                                         &slot.imageAvailable), "imageAvailable") ||
                !check(vkCreateSemaphore(shared->device, &semaphoreInfo, nullptr,
                                         &slot.renderFinished), "renderFinished") ||
                !check(vkCreateFence(shared->device, &fenceInfo, nullptr, &slot.fence),
                       "frameFence")) {
                return false;
            }
        }
        return true;
    }

    bool Engine::Impl::createVertexBuffer() {
        // Triangle strip in NDC. KTX2 layers are uploaded top-down, but the
        // WindowSurface's V axis is opposite, so the top vertices sample v=1.
        // NDC 三角带；KTX2 layer 按 top-down 上传，但 WindowSurface 的 V 轴方向相反，
        // 因此顶部顶点采样 v=1。
        const float vertices[] = {
                -1.f, 1.f, 0.f, 1.f, 0.f, 1.f,
                -1.f, -1.f, 0.f, 0.f, 0.f, 0.f,
                1.f, 1.f, 1.f, 1.f, 1.f, 1.f,
                1.f, -1.f, 1.f, 0.f, 1.f, 0.f,
        };
        VkBufferCreateInfo bufferInfo{
                .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .size = sizeof(vertices),
                .usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
                .queueFamilyIndexCount = 0,
                .pQueueFamilyIndices = nullptr,
        };
        if (!check(vkCreateBuffer(shared->device, &bufferInfo, nullptr, &vertexBuffer),
                   "vertexBuffer")) {
            return false;
        }
        VkMemoryRequirements requirements{};
        vkGetBufferMemoryRequirements(shared->device, vertexBuffer, &requirements);
        const uint32_t memoryType = findMemoryType(
                shared->memoryProps, requirements.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (memoryType == UINT32_MAX) return false;
        VkMemoryAllocateInfo allocateInfo{
                .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                .pNext = nullptr,
                .allocationSize = requirements.size,
                .memoryTypeIndex = memoryType,
        };
        if (!check(vkAllocateMemory(shared->device, &allocateInfo, nullptr, &vertexMemory),
                   "vertexMemory") ||
            !check(vkBindBufferMemory(shared->device, vertexBuffer, vertexMemory, 0),
                   "bindVertexBuffer")) {
            return false;
        }
        void *mapped = nullptr;
        if (!check(vkMapMemory(shared->device, vertexMemory, 0, sizeof(vertices), 0, &mapped),
                   "mapVertexBuffer")) {
            return false;
        }
        std::memcpy(mapped, vertices, sizeof(vertices));
        vkUnmapMemory(shared->device, vertexMemory);
        return true;
    }

    bool Engine::Impl::ensureRenderPass(VkFormat format) {
        if (renderPass && swapFormat == format) return true;
        if (seqPipeline) {
            vkDestroyPipeline(shared->device, seqPipeline, nullptr);
            seqPipeline = VK_NULL_HANDLE;
        }
        if (renderPass) {
            vkDestroyRenderPass(shared->device, renderPass, nullptr);
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
        VkAttachmentReference colorRef{
                .attachment = 0,
                .layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
        };
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
        VkSubpassDependency dependency{
                .srcSubpass = VK_SUBPASS_EXTERNAL,
                .dstSubpass = 0,
                .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                .srcAccessMask = 0,
                .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                .dependencyFlags = 0,
        };
        VkRenderPassCreateInfo createInfo{
                .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .attachmentCount = 1,
                .pAttachments = &color,
                .subpassCount = 1,
                .pSubpasses = &subpass,
                .dependencyCount = 1,
                .pDependencies = &dependency,
        };
        return check(vkCreateRenderPass(shared->device, &createInfo, nullptr, &renderPass),
                     "renderPass");
    }

    bool Engine::Impl::createSwapchain(int width, int height) {
        destroySwapchain();
        if (!surface || !window) return false;

        VkSurfaceCapabilitiesKHR caps{};
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(shared->physical, surface, &caps);
        uint32_t formatCount = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(shared->physical, surface, &formatCount, nullptr);
        if (formatCount == 0) {
            KTX_LOGE("swapchain: no surface formats");
            return false;
        }
        std::vector<VkSurfaceFormatKHR> formats(formatCount);
        vkGetPhysicalDeviceSurfaceFormatsKHR(shared->physical, surface, &formatCount,
                                             formats.data());
        VkSurfaceFormatKHR chosen = formats[0];
        for (const auto &format : formats) {
            if (format.format == VK_FORMAT_R8G8B8A8_UNORM ||
                format.format == VK_FORMAT_B8G8R8A8_UNORM) {
                chosen = format;
                break;
            }
        }
        if (!ensureRenderPass(chosen.format)) return false;

        swapExtent.width = (uint32_t) std::max(1, width);
        swapExtent.height = (uint32_t) std::max(1, height);
        if (caps.currentExtent.width != UINT32_MAX) {
            swapExtent = caps.currentExtent;
        } else {
            swapExtent.width = std::clamp(swapExtent.width, caps.minImageExtent.width,
                                          caps.maxImageExtent.width);
            swapExtent.height = std::clamp(swapExtent.height, caps.minImageExtent.height,
                                           caps.maxImageExtent.height);
        }
        uint32_t imageCount = caps.minImageCount + 1;
        if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount) {
            imageCount = caps.maxImageCount;
        }
        VkSwapchainCreateInfoKHR swapInfo{
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
                .preTransform = ((caps.supportedTransforms &
                                  VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) != 0)
                        ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                        : caps.currentTransform,
                .compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
                .presentMode = VK_PRESENT_MODE_FIFO_KHR,
                .clipped = VK_TRUE,
                .oldSwapchain = VK_NULL_HANDLE,
        };
        if ((caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR) == 0) {
            if ((caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR) != 0) {
                swapInfo.compositeAlpha = VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR;
            } else if ((caps.supportedCompositeAlpha &
                        VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR) != 0) {
                swapInfo.compositeAlpha = VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR;
            } else {
                swapInfo.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
            }
        }
        if (!check(vkCreateSwapchainKHR(shared->device, &swapInfo, nullptr, &swapchain),
                   "swapchain")) {
            return false;
        }
        uint32_t imageTotal = 0;
        vkGetSwapchainImagesKHR(shared->device, swapchain, &imageTotal, nullptr);
        swapImages.resize(imageTotal);
        vkGetSwapchainImagesKHR(shared->device, swapchain, &imageTotal, swapImages.data());
        swapViews.resize(imageTotal);
        framebuffers.resize(imageTotal);
        for (uint32_t index = 0; index < imageTotal; index++) {
            VkImageViewCreateInfo viewInfo{
                    .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
                    .pNext = nullptr,
                    .flags = 0,
                    .image = swapImages[index],
                    .viewType = VK_IMAGE_VIEW_TYPE_2D,
                    .format = chosen.format,
                    .components = {},
                    .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
            };
            if (!check(vkCreateImageView(shared->device, &viewInfo, nullptr, &swapViews[index]),
                       "swapView")) {
                return false;
            }
            VkFramebufferCreateInfo framebufferInfo{
                    .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
                    .pNext = nullptr,
                    .flags = 0,
                    .renderPass = renderPass,
                    .attachmentCount = 1,
                    .pAttachments = &swapViews[index],
                    .width = swapExtent.width,
                    .height = swapExtent.height,
                    .layers = 1,
            };
            if (!check(vkCreateFramebuffer(shared->device, &framebufferInfo, nullptr,
                                           &framebuffers[index]), "framebuffer")) {
                return false;
            }
        }
        KTX_LOGI("Swapchain %ux%u format=%d images=%u", swapExtent.width, swapExtent.height,
                 (int) chosen.format, imageTotal);
        return true;
    }

    void Engine::Impl::destroySwapchain() {
        outputBinding.invalidate();
        waitRetireAllFrames();
        for (auto framebuffer : framebuffers) {
            if (framebuffer) vkDestroyFramebuffer(shared->device, framebuffer, nullptr);
        }
        framebuffers.clear();
        for (auto view : swapViews) {
            if (view) vkDestroyImageView(shared->device, view, nullptr);
        }
        swapViews.clear();
        swapImages.clear();
        if (swapchain) {
            vkDestroySwapchainKHR(shared->device, swapchain, nullptr);
            swapchain = VK_NULL_HANDLE;
        }
        // Keep renderPass / pipeline hot: they depend only on the swapchain format,
        // not on the Surface. Recreating the Surface (Idle/Think/Speech switch)
        // should not recompile shaders. ensureRenderPass() rebuilds them if the
        // format actually changes.
        // 保留 renderPass / pipeline：它们只依赖交换链格式，与 Surface 无关；
        // 切换球重建 Surface 时不再重新编译着色器。格式真的变化时由
        // ensureRenderPass() 重建。
        swapExtent = {0, 0};
    }

    bool Engine::Impl::createGraphicsPipeline() {
        if (!renderPass || !seqPipelineLayout) return false;
        if (seqPipeline) {
            vkDestroyPipeline(shared->device, seqPipeline, nullptr);
            seqPipeline = VK_NULL_HANDLE;
        }
        if (colorEnabled && !colorModule) {
            VkShaderModuleCreateInfo colorInfo{
                    .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
                    .pNext = nullptr, .flags = 0,
                    .codeSize = sizeof(kColorFrag), .pCode = kColorFrag,
            };
            if (!check(vkCreateShaderModule(shared->device, &colorInfo, nullptr, &colorModule),
                       "colorShader")) return false;
        }
        VkPipelineShaderStageCreateInfo stages[2] = {
                {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                        .pNext = nullptr,
                        .flags = 0,
                        .stage = VK_SHADER_STAGE_VERTEX_BIT,
                        .module = shared->vertModule,
                        .pName = "main",
                        .pSpecializationInfo = nullptr},
                {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                        .pNext = nullptr,
                        .flags = 0,
                        .stage = VK_SHADER_STAGE_FRAGMENT_BIT,
                        .module = colorEnabled ? colorModule : shared->fragModule,
                        .pName = "main",
                        .pSpecializationInfo = nullptr},
        };
        VkVertexInputBindingDescription binding{
                .binding = 0,
                .stride = kVertexStride,
                .inputRate = VK_VERTEX_INPUT_RATE_VERTEX,
        };
        VkVertexInputAttributeDescription attributes[3] = {
                {.location = 0, .binding = 0, .format = VK_FORMAT_R32G32_SFLOAT, .offset = 0},
                {.location = 1, .binding = 0, .format = VK_FORMAT_R32G32_SFLOAT,
                        .offset = kVertexOffsetUvAlpha},
                {.location = 2, .binding = 0, .format = VK_FORMAT_R32G32_SFLOAT,
                        .offset = kVertexOffsetUvRgb},
        };
        VkPipelineVertexInputStateCreateInfo vertexInput{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .vertexBindingDescriptionCount = 1,
                .pVertexBindingDescriptions = &binding,
                .vertexAttributeDescriptionCount = 3,
                .pVertexAttributeDescriptions = attributes,
        };
        VkPipelineInputAssemblyStateCreateInfo assembly{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP,
                .primitiveRestartEnable = VK_FALSE,
        };
        VkPipelineViewportStateCreateInfo viewport{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .viewportCount = 1,
                .pViewports = nullptr,
                .scissorCount = 1,
                .pScissors = nullptr,
        };
        VkPipelineRasterizationStateCreateInfo raster{
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
        VkPipelineMultisampleStateCreateInfo multisample{
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
        VkPipelineColorBlendAttachmentState blendAttachment{
                .blendEnable = VK_FALSE,
                .srcColorBlendFactor = VK_BLEND_FACTOR_ONE,
                .dstColorBlendFactor = VK_BLEND_FACTOR_ZERO,
                .colorBlendOp = VK_BLEND_OP_ADD,
                .srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE,
                .dstAlphaBlendFactor = VK_BLEND_FACTOR_ZERO,
                .alphaBlendOp = VK_BLEND_OP_ADD,
                .colorWriteMask = static_cast<VkColorComponentFlags>(
                        VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                        VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT),
        };
        VkPipelineColorBlendStateCreateInfo blend{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .logicOpEnable = VK_FALSE,
                .logicOp = VK_LOGIC_OP_COPY,
                .attachmentCount = 1,
                .pAttachments = &blendAttachment,
                .blendConstants = {0.f, 0.f, 0.f, 0.f},
        };
        VkDynamicState dynamicStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo dynamic{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .dynamicStateCount = 2,
                .pDynamicStates = dynamicStates,
        };
        VkGraphicsPipelineCreateInfo pipelineInfo{
                .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .stageCount = 2,
                .pStages = stages,
                .pVertexInputState = &vertexInput,
                .pInputAssemblyState = &assembly,
                .pTessellationState = nullptr,
                .pViewportState = &viewport,
                .pRasterizationState = &raster,
                .pMultisampleState = &multisample,
                .pDepthStencilState = nullptr,
                .pColorBlendState = &blend,
                .pDynamicState = &dynamic,
                .layout = seqPipelineLayout,
                .renderPass = renderPass,
                .subpass = 0,
                .basePipelineHandle = VK_NULL_HANDLE,
                .basePipelineIndex = -1,
        };
        return check(vkCreateGraphicsPipelines(shared->device, VK_NULL_HANDLE, 1,
                                               &pipelineInfo, nullptr, &seqPipeline),
                     "seqPipeline");
    }

    bool Engine::Impl::ensurePipeline() {
        if (seqPipeline) return true;
        return createGraphicsPipeline();
    }

    void Engine::Impl::destroyUploadBuffer(FrameSlot &slot) {
        if (slot.uploadMapped) {
            vkUnmapMemory(shared->device, slot.uploadMemory);
            slot.uploadMapped = nullptr;
        }
        if (slot.uploadBuffer) {
            vkDestroyBuffer(shared->device, slot.uploadBuffer, nullptr);
            slot.uploadBuffer = VK_NULL_HANDLE;
        }
        if (slot.uploadMemory) {
            vkFreeMemory(shared->device, slot.uploadMemory, nullptr);
            slot.uploadMemory = VK_NULL_HANDLE;
        }
        slot.uploadCapacity = 0;
    }

    void Engine::Impl::destroySequenceResources() {
        if (seqPipeline) {
            vkDestroyPipeline(shared->device, seqPipeline, nullptr);
            seqPipeline = VK_NULL_HANDLE;
        }
        if (seqPipelineLayout) {
            vkDestroyPipelineLayout(shared->device, seqPipelineLayout, nullptr);
            seqPipelineLayout = VK_NULL_HANDLE;
        }
        if (seqDescPool) {
            vkDestroyDescriptorPool(shared->device, seqDescPool, nullptr);
            seqDescPool = VK_NULL_HANDLE;
            seqDescSet = VK_NULL_HANDLE;
        }
        if (seqSetLayout) {
            vkDestroyDescriptorSetLayout(shared->device, seqSetLayout, nullptr);
            seqSetLayout = VK_NULL_HANDLE;
        }
        if (seqSampler) {
            vkDestroySampler(shared->device, seqSampler, nullptr);
            seqSampler = VK_NULL_HANDLE;
        }
        if (seqView) {
            vkDestroyImageView(shared->device, seqView, nullptr);
            seqView = VK_NULL_HANDLE;
        }
        if (seqImage) {
            vkDestroyImage(shared->device, seqImage, nullptr);
            seqImage = VK_NULL_HANDLE;
        }
        if (seqMemory) {
            vkFreeMemory(shared->device, seqMemory, nullptr);
            seqMemory = VK_NULL_HANDLE;
        }
        seqLayers = 0;
        seqWidth = 0;
        seqHeight = 0;
        seqLayerBytes = 0;
    }

    bool Engine::Impl::setTextureArray(const uint8_t *data, size_t length) {
        KtxHeader header{};
        if (!parseKtx2(data, length, header)) return false;
        return setTextureArrayRaw(
                header.format, header.width, header.height, header.layers,
                data + header.levelOffset, header.levelLength);
    }

    bool Engine::Impl::setTextureArrayZstd(
            VkFormat format,
            uint32_t width,
            uint32_t height,
            uint32_t layers,
            const uint8_t *compressed,
            size_t compressedLength,
            size_t uncompressedLength) {
        if (!compressed || compressedLength == 0 || uncompressedLength == 0) return false;
        const auto decompressStart = std::chrono::steady_clock::now();
        std::vector<uint8_t> raw(uncompressedLength);
        const size_t decoded = ZSTD_decompress(
                raw.data(), raw.size(), compressed, compressedLength);
        const auto decompressEnd = std::chrono::steady_clock::now();
        if (ZSTD_isError(decoded) || decoded != uncompressedLength) {
            KTX_LOGE("ZSTD_decompress failed: %s", ZSTD_getErrorName(decoded));
            return false;
        }
        const bool ok = setTextureArrayRaw(
                format, width, height, layers, raw.data(), raw.size());
        const auto uploadEnd = std::chrono::steady_clock::now();
        KTX_LOGI(
                "zstd level decompress=%lldms upload=%lldms stored=%zu decoded=%zu",
                (long long) std::chrono::duration_cast<std::chrono::milliseconds>(
                        decompressEnd - decompressStart).count(),
                (long long) std::chrono::duration_cast<std::chrono::milliseconds>(
                        uploadEnd - decompressEnd).count(),
                compressedLength, uncompressedLength);
        return ok;
    }

    bool Engine::Impl::setTextureArrayRaw(
            VkFormat format,
            uint32_t width,
            uint32_t height,
            uint32_t layers,
            const uint8_t *data,
            size_t length, int fd, int64_t offset) {
        if (!shared || (!data && fd < 0) || width == 0 || height == 0 || layers == 0) return false;
        // KtxSurfacePlayer serializes attach/upload/present/close for this engine.
        // These resources and the command pool are instance-owned: preparation and
        // pipeline compilation must not block other players' shared queue.
        const auto prepareStart = std::chrono::steady_clock::now();
        if (!formatSupported(format, *shared)) {
            KTX_LOGE("unsupported texture format %d", (int) format);
            return false;
        }
        const uint64_t expectedLayerBytes = layerBytesForFormat(format, width, height);
        if (expectedLayerBytes == 0 ||
            length != expectedLayerBytes * (uint64_t) layers) {
            KTX_LOGE("raw texture length %zu does not match %ux%u x %u layers", length,
                     width, height, layers);
            return false;
        }
        if (!waitRetireAllFrames()) return false;
        destroySequenceResources();

        VkDescriptorSetLayoutBinding binding{
                .binding = 0,
                .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                .descriptorCount = 1,
                .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
                .pImmutableSamplers = nullptr,
        };
        VkDescriptorSetLayoutCreateInfo setLayoutInfo{
                .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .bindingCount = 1,
                .pBindings = &binding,
        };
        if (!check(vkCreateDescriptorSetLayout(shared->device, &setLayoutInfo, nullptr,
                                               &seqSetLayout), "seqSetLayout")) {
            destroySequenceResources();
            return false;
        }
        VkDescriptorPoolSize poolSize{
                .type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                .descriptorCount = 1,
        };
        VkDescriptorPoolCreateInfo poolInfo{
                .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .maxSets = 1,
                .poolSizeCount = 1,
                .pPoolSizes = &poolSize,
        };
        if (!check(vkCreateDescriptorPool(shared->device, &poolInfo, nullptr, &seqDescPool),
                   "seqDescPool")) {
            destroySequenceResources();
            return false;
        }
        VkDescriptorSetAllocateInfo setInfo{
                .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
                .pNext = nullptr,
                .descriptorPool = seqDescPool,
                .descriptorSetCount = 1,
                .pSetLayouts = &seqSetLayout,
        };
        if (!check(vkAllocateDescriptorSets(shared->device, &setInfo, &seqDescSet),
                   "seqDescSet")) {
            destroySequenceResources();
            return false;
        }
        VkPushConstantRange pushRange{
                .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
                .offset = 0,
                .size = colorEnabled ? uint32_t(sizeof(ColorPush)) : uint32_t(sizeof(uint32_t)),
        };
        VkPipelineLayoutCreateInfo layoutInfo{
                .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .setLayoutCount = 1,
                .pSetLayouts = &seqSetLayout,
                .pushConstantRangeCount = 1,
                .pPushConstantRanges = &pushRange,
        };
        if (!check(vkCreatePipelineLayout(shared->device, &layoutInfo, nullptr,
                                          &seqPipelineLayout), "seqPipelineLayout")) {
            destroySequenceResources();
            return false;
        }

        VkImageCreateInfo imageInfo{
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
        if (!check(vkCreateImage(shared->device, &imageInfo, nullptr, &seqImage), "seqImage")) {
            destroySequenceResources();
            return false;
        }
        VkMemoryRequirements imageRequirements{};
        vkGetImageMemoryRequirements(shared->device, seqImage, &imageRequirements);
        const uint32_t imageMemoryType = findMemoryType(
                shared->memoryProps, imageRequirements.memoryTypeBits,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (imageMemoryType == UINT32_MAX) {
            destroySequenceResources();
            return false;
        }
        VkMemoryAllocateInfo imageMemoryInfo{
                .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                .pNext = nullptr,
                .allocationSize = imageRequirements.size,
                .memoryTypeIndex = imageMemoryType,
        };
        if (!check(vkAllocateMemory(shared->device, &imageMemoryInfo, nullptr, &seqMemory),
                   "seqMemory") ||
            !check(vkBindImageMemory(shared->device, seqImage, seqMemory, 0), "bindSeqImage")) {
            destroySequenceResources();
            return false;
        }
        VkImageViewCreateInfo viewInfo{
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
        if (!check(vkCreateImageView(shared->device, &viewInfo, nullptr, &seqView),
                   "seqView")) {
            destroySequenceResources();
            return false;
        }
        VkSamplerCreateInfo samplerInfo{
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
        if (!check(vkCreateSampler(shared->device, &samplerInfo, nullptr, &seqSampler),
                   "seqSampler")) {
            destroySequenceResources();
            return false;
        }
        VkDescriptorImageInfo descriptorImage{
                .sampler = seqSampler,
                .imageView = seqView,
                .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        };
        VkWriteDescriptorSet descriptorWrite{
                .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                .pNext = nullptr,
                .dstSet = seqDescSet,
                .dstBinding = 0,
                .dstArrayElement = 0,
                .descriptorCount = 1,
                .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                .pImageInfo = &descriptorImage,
                .pBufferInfo = nullptr,
                .pTexelBufferView = nullptr,
        };
        vkUpdateDescriptorSets(shared->device, 1, &descriptorWrite, 0, nullptr);

        VkBuffer staging = VK_NULL_HANDLE;
        VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
        auto destroyStaging = [&]() {
            if (staging) vkDestroyBuffer(shared->device, staging, nullptr);
            if (stagingMemory) vkFreeMemory(shared->device, stagingMemory, nullptr);
            staging = VK_NULL_HANDLE;
            stagingMemory = VK_NULL_HANDLE;
        };
        VkBufferCreateInfo stagingInfo{
                .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .size = length,
                .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
                .queueFamilyIndexCount = 0,
                .pQueueFamilyIndices = nullptr,
        };
        if (!check(vkCreateBuffer(shared->device, &stagingInfo, nullptr, &staging),
                   "ktxStaging")) {
            destroySequenceResources();
            return false;
        }
        VkMemoryRequirements stagingRequirements{};
        vkGetBufferMemoryRequirements(shared->device, staging, &stagingRequirements);
        const uint32_t stagingType = findMemoryType(
                shared->memoryProps, stagingRequirements.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (stagingType == UINT32_MAX) {
            destroyStaging();
            destroySequenceResources();
            return false;
        }
        VkMemoryAllocateInfo stagingMemoryInfo{
                .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                .pNext = nullptr,
                .allocationSize = stagingRequirements.size,
                .memoryTypeIndex = stagingType,
        };
        if (!check(vkAllocateMemory(shared->device, &stagingMemoryInfo, nullptr, &stagingMemory),
                   "ktxStagingMemory") ||
            !check(vkBindBufferMemory(shared->device, staging, stagingMemory, 0),
                   "bindKtxStaging")) {
            destroyStaging();
            destroySequenceResources();
            return false;
        }
        void *mapped = nullptr;
        if (!check(vkMapMemory(shared->device, stagingMemory, 0, length, 0, &mapped),
                   "mapKtxStaging")) {
            destroyStaging();
            destroySequenceResources();
            return false;
        }
        const int readError = data ? 0 : kap::readFrameAt(fd, offset, mapped, length);
        if (data) std::memcpy(mapped, data, (size_t) length);
        vkUnmapMemory(shared->device, stagingMemory);
        if (readError != 0) {
            KTX_LOGE("initial staging read failed: errno=%d", readError);
            destroyStaging();
            destroySequenceResources();
            return false;
        }

        VkCommandBuffer command = VK_NULL_HANDLE;
        VkCommandBufferAllocateInfo commandInfo{
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                .pNext = nullptr,
                .commandPool = cmdPool,
                .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                .commandBufferCount = 1,
        };
        if (!check(vkAllocateCommandBuffers(shared->device, &commandInfo, &command),
                   "uploadCommand")) {
            destroyStaging();
            destroySequenceResources();
            return false;
        }
        VkCommandBufferBeginInfo beginInfo{
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                .pNext = nullptr,
                .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
                .pInheritanceInfo = nullptr,
        };
        vkBeginCommandBuffer(command, &beginInfo);
        VkImageMemoryBarrier toTransfer{
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
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                             &toTransfer);
        VkBufferImageCopy copyRegion{
                .bufferOffset = 0,
                .bufferRowLength = 0,
                .bufferImageHeight = 0,
                .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, layers},
                .imageOffset = {0, 0, 0},
                .imageExtent = {width, height, 1},
        };
        vkCmdCopyBufferToImage(command, staging, seqImage,
                               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copyRegion);
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
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr,
                             1, &toSample);
        vkEndCommandBuffer(command);
        VkFenceCreateInfo fenceInfo{
                .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
        };
        VkFence fence = VK_NULL_HANDLE;
        if (!check(vkCreateFence(shared->device, &fenceInfo, nullptr, &fence),
                   "uploadFence")) {
            vkFreeCommandBuffers(shared->device, cmdPool, 1, &command);
            destroyStaging();
            destroySequenceResources();
            return false;
        }
        VkSubmitInfo submitInfo{
                .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                .pNext = nullptr,
                .waitSemaphoreCount = 0,
                .pWaitSemaphores = nullptr,
                .pWaitDstStageMask = nullptr,
                .commandBufferCount = 1,
                .pCommandBuffers = &command,
                .signalSemaphoreCount = 0,
                .pSignalSemaphores = nullptr,
        };
        const auto prepareEnd = std::chrono::steady_clock::now();
        VkResult submitResult;
        std::chrono::steady_clock::time_point queueAcquired;
        {
            // VkQueue is shared and requires external synchronization; only the
            // submission belongs under this lock, not its GPU completion wait.
            QueueGuard lock(shared);
            queueAcquired = std::chrono::steady_clock::now();
            submitResult = vkQueueSubmit(shared->graphicsQueue, 1, &submitInfo, fence);
        }
        if (submitResult != VK_SUCCESS ||
            vkWaitForFences(shared->device, 1, &fence, VK_TRUE, UINT64_MAX) != VK_SUCCESS) {
            vkDestroyFence(shared->device, fence, nullptr);
            vkFreeCommandBuffers(shared->device, cmdPool, 1, &command);
            destroyStaging();
            destroySequenceResources();
            return false;
        }
        vkDestroyFence(shared->device, fence, nullptr);
        vkFreeCommandBuffers(shared->device, cmdPool, 1, &command);
        destroyStaging();
        const auto uploadEnd = std::chrono::steady_clock::now();

        seqLayers = layers;
        seqWidth = width;
        seqHeight = height;
        seqLayerBytes = (uint32_t) (length / layers);
        if (renderPass && !ensurePipeline()) {
            destroySequenceResources();
            return false;
        }
        const auto pipelineEnd = std::chrono::steady_clock::now();
        const auto milliseconds = [](auto duration) {
            return (long long) std::chrono::duration_cast<std::chrono::milliseconds>(duration).count();
        };
        KTX_LOGI("upload timing prepare=%lldms queue_wait=%lldms submit_and_gpu=%lldms pipeline=%lldms",
                 milliseconds(prepareEnd - prepareStart), milliseconds(queueAcquired - prepareEnd),
                 milliseconds(uploadEnd - queueAcquired), milliseconds(pipelineEnd - uploadEnd));
        KTX_LOGI("Texture array ready: %ux%u layers=%u format=%d layerBytes=%u",
                 seqWidth, seqHeight, seqLayers, (int) format, seqLayerBytes);
        return true;
    }

    bool Engine::Impl::ensureUploadBuffer(FrameSlot &slot, size_t size) {
        if (slot.uploadCapacity >= size && slot.uploadMapped != nullptr) return true;
        destroyUploadBuffer(slot);
        VkBufferCreateInfo bufferInfo{
                .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .size = size,
                .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
                .queueFamilyIndexCount = 0,
                .pQueueFamilyIndices = nullptr,
        };
        if (!check(vkCreateBuffer(shared->device, &bufferInfo, nullptr, &slot.uploadBuffer),
                   "streamUploadBuffer")) {
            return false;
        }
        VkMemoryRequirements requirements{};
        vkGetBufferMemoryRequirements(shared->device, slot.uploadBuffer, &requirements);
        const uint32_t memoryType = findMemoryType(
                shared->memoryProps, requirements.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (memoryType == UINT32_MAX) return false;
        VkMemoryAllocateInfo allocateInfo{
                .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                .pNext = nullptr,
                .allocationSize = requirements.size,
                .memoryTypeIndex = memoryType,
        };
        if (!check(vkAllocateMemory(shared->device, &allocateInfo, nullptr,
                                    &slot.uploadMemory), "streamUploadMemory") ||
            !check(vkBindBufferMemory(shared->device, slot.uploadBuffer, slot.uploadMemory, 0),
                   "bindStreamUpload")) {
            return false;
        }
        if (!check(vkMapMemory(shared->device, slot.uploadMemory, 0, size, 0,
                               &slot.uploadMapped), "mapStreamUpload")) {
            return false;
        }
        slot.uploadCapacity = size;
        return true;
    }

    void Engine::Impl::recordRenderCommands(
            FrameSlot &slot,
            uint32_t imageIndex,
            uint32_t layer) {
        vkResetCommandBuffer(slot.cmd, 0);
        VkCommandBufferBeginInfo beginInfo{
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                .pNext = nullptr,
                .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
                .pInheritanceInfo = nullptr,
        };
        vkBeginCommandBuffer(slot.cmd, &beginInfo);
        VkClearValue clear{.color = {{0.f, 0.f, 0.f, 0.f}}};
        VkRenderPassBeginInfo renderPassInfo{
                .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
                .pNext = nullptr,
                .renderPass = renderPass,
                .framebuffer = framebuffers[imageIndex],
                .renderArea = {{0, 0}, swapExtent},
                .clearValueCount = 1,
                .pClearValues = &clear,
        };
        vkCmdBeginRenderPass(slot.cmd, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, seqPipeline);
        VkViewport viewport{
                0.f,
                0.f,
                (float) swapExtent.width,
                (float) swapExtent.height,
                0.f,
                1.f,
        };
        VkRect2D scissor{{0, 0}, swapExtent};
        vkCmdSetViewport(slot.cmd, 0, 1, &viewport);
        vkCmdSetScissor(slot.cmd, 0, 1, &scissor);
        VkDeviceSize offset = 0;
        vkCmdBindVertexBuffers(slot.cmd, 0, 1, &vertexBuffer, &offset);
        vkCmdBindDescriptorSets(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, seqPipelineLayout,
                                0, 1, &seqDescSet, 0, nullptr);
        const uint32_t layerIndex = layer % seqLayers;
        pushLayer(slot.cmd, layerIndex);
        vkCmdDraw(slot.cmd, 4, 1, 0, 0);
        vkCmdEndRenderPass(slot.cmd);
        vkEndCommandBuffer(slot.cmd);
    }

    void Engine::Impl::recordUploadAndRenderCommands(
            FrameSlot &slot,
            uint32_t imageIndex,
            uint32_t layer) {
        vkResetCommandBuffer(slot.cmd, 0);
        VkCommandBufferBeginInfo beginInfo{
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                .pNext = nullptr,
                .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
                .pInheritanceInfo = nullptr,
        };
        vkBeginCommandBuffer(slot.cmd, &beginInfo);
        VkImageMemoryBarrier toTransfer{
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
                             &toTransfer);
        VkBufferImageCopy copyRegion{
                .bufferOffset = 0,
                .bufferRowLength = 0,
                .bufferImageHeight = 0,
                .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, layer, 1},
                .imageOffset = {0, 0, 0},
                .imageExtent = {seqWidth, seqHeight, 1},
        };
        vkCmdCopyBufferToImage(slot.cmd, slot.uploadBuffer, seqImage,
                               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copyRegion);
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
        VkClearValue clear{.color = {{0.f, 0.f, 0.f, 0.f}}};
        VkRenderPassBeginInfo renderPassInfo{
                .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
                .pNext = nullptr,
                .renderPass = renderPass,
                .framebuffer = framebuffers[imageIndex],
                .renderArea = {{0, 0}, swapExtent},
                .clearValueCount = 1,
                .pClearValues = &clear,
        };
        vkCmdBeginRenderPass(slot.cmd, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, seqPipeline);
        VkViewport viewport{
                0.f,
                0.f,
                (float) swapExtent.width,
                (float) swapExtent.height,
                0.f,
                1.f,
        };
        VkRect2D scissor{{0, 0}, swapExtent};
        vkCmdSetViewport(slot.cmd, 0, 1, &viewport);
        vkCmdSetScissor(slot.cmd, 0, 1, &scissor);
        VkDeviceSize offset = 0;
        vkCmdBindVertexBuffers(slot.cmd, 0, 1, &vertexBuffer, &offset);
        vkCmdBindDescriptorSets(slot.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, seqPipelineLayout,
                                0, 1, &seqDescSet, 0, nullptr);
        const uint32_t layerIndex = layer;
        pushLayer(slot.cmd, layerIndex);
        vkCmdDraw(slot.cmd, 4, 1, 0, 0);
        vkCmdEndRenderPass(slot.cmd);
        vkEndCommandBuffer(slot.cmd);
    }

    void Engine::Impl::pushLayer(VkCommandBuffer command, uint32_t layer) {
        if (colorEnabled) {
            colorPush.layer = layer;
            vkCmdPushConstants(command, seqPipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0,
                               sizeof(colorPush), &colorPush);
        } else {
            vkCmdPushConstants(command, seqPipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0,
                               sizeof(layer), &layer);
        }
    }

    bool Engine::Impl::submitAndPresent(FrameSlot &slot, uint32_t imageIndex) {
        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submitInfo{
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
        if (!check(vkQueueSubmit(shared->graphicsQueue, 1, &submitInfo, slot.fence),
                   "queueSubmit")) {
            outputBinding.invalidate();
            return false;
        }
        VkPresentInfoKHR presentInfo{
                .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
                .pNext = nullptr,
                .waitSemaphoreCount = 1,
                .pWaitSemaphores = &slot.renderFinished,
                .swapchainCount = 1,
                .pSwapchains = &swapchain,
                .pImageIndices = &imageIndex,
                .pResults = nullptr,
        };
        const VkResult presentResult = vkQueuePresentKHR(shared->graphicsQueue, &presentInfo);
        slot.submitted = true;
        frameIndex = (frameIndex + 1) % kFramesInFlight;
        if (presentResult == VK_ERROR_OUT_OF_DATE_KHR ||
            presentResult == VK_SUBOPTIMAL_KHR) {
            waitRetireAllFrames();
            createSwapchain((int) swapExtent.width, (int) swapExtent.height);
            return false;
        }
        if (presentResult != VK_SUCCESS) {
            outputBinding.invalidate();
            KTX_LOGE("vkQueuePresentKHR failed: %d", (int) presentResult);
            return false;
        }
        return true;
    }

    bool Engine::Impl::present(uint32_t layer) {
        if (!readyInternal() || !ensurePipeline()) return false;
        FrameSlot &slot = frames[frameIndex];
        if (!waitRetireFrame(slot, 0)) return false;
        if (!check(vkResetFences(shared->device, 1, &slot.fence), "resetFrameFence")) {
            outputBinding.invalidate();
            return false;
        }
        uint32_t imageIndex = 0;
        const VkResult acquire = vkAcquireNextImageKHR(
                shared->device, swapchain, kAcquireTimeoutNanos, slot.imageAvailable,
                VK_NULL_HANDLE, &imageIndex);
        if (acquire == VK_ERROR_OUT_OF_DATE_KHR) {
            waitRetireAllFrames();
            createSwapchain((int) swapExtent.width, (int) swapExtent.height);
            return false;
        }
        // SUBOPTIMAL still acquired an image and signalled imageAvailable. Submit
        // it normally; discarding it would reuse a signalled binary semaphore.
        if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR) {
            if (acquire != VK_TIMEOUT && acquire != VK_NOT_READY) {
                outputBinding.invalidate();
                KTX_LOGE("vkAcquireNextImageKHR failed: %d", (int) acquire);
            }
            return false;
        }
        recordRenderCommands(slot, imageIndex, layer);
        return submitAndPresent(slot, imageIndex);
    }

    bool Engine::Impl::presentFile(uint32_t layer, int fd, int64_t offset, size_t length,
                                   int *ioError, const void *bytes) {
        if (length != seqLayerBytes || layer >= seqLayers) return false;
        if (!readyInternal() || !ensurePipeline()) return false;
        FrameSlot &slot = frames[frameIndex];
        if (!waitRetireFrame(slot, 0)) return false;
        // Rewriting a ring layer while an older frame in another slot still samples it
        // would be a GPU read/write race. Wait for every slot when the ring is tiny.
        // ring 很小时，覆盖某层可能与其他槽位在途帧冲突；先等所有槽位。
        if (seqLayers < kFramesInFlight && !waitRetireAllFrames(0)) return false;
        if (!ensureUploadBuffer(slot, length)) return false;
        // File I/O must stay outside the process-wide queue lock. The owner
        // serializes this engine, and the slot fence has retired its last use.
        if (bytes) {
            std::memcpy(slot.uploadMapped, bytes, length);
        } else {
            *ioError = kap::readFrameAt(fd, offset, slot.uploadMapped, length);
        }
        if (*ioError != 0) return false;
        QueueGuard lock(shared);
        if (!check(vkResetFences(shared->device, 1, &slot.fence), "resetStreamFence")) {
            outputBinding.invalidate();
            return false;
        }
        uint32_t imageIndex = 0;
        const VkResult acquire = vkAcquireNextImageKHR(
                shared->device, swapchain, kAcquireTimeoutNanos, slot.imageAvailable,
                VK_NULL_HANDLE, &imageIndex);
        if (acquire == VK_ERROR_OUT_OF_DATE_KHR) {
            waitRetireAllFrames();
            createSwapchain((int) swapExtent.width, (int) swapExtent.height);
            return false;
        }
        if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR) {
            if (acquire != VK_TIMEOUT && acquire != VK_NOT_READY) {
                outputBinding.invalidate();
                KTX_LOGE("vkAcquireNextImageKHR failed: %d", (int) acquire);
            }
            return false;
        }
        recordUploadAndRenderCommands(slot, imageIndex, layer);
        return submitAndPresent(slot, imageIndex);
    }

    bool Engine::Impl::waitRetireFrame(FrameSlot &slot, uint64_t timeout) {
        if (!slot.submitted) return true;
        const VkResult result = vkWaitForFences(
                shared->device, 1, &slot.fence, VK_TRUE, timeout);
        if (result == VK_TIMEOUT) return false;
        if (result != VK_SUCCESS) {
            KTX_LOGE("vkWaitForFences failed: %d", (int) result);
            return false;
        }
        slot.submitted = false;
        return true;
    }

    bool Engine::Impl::waitRetireAllFrames(uint64_t timeout) {
        for (auto &slot : frames) {
            if (!waitRetireFrame(slot, timeout)) return false;
        }
        return true;
    }

    bool Engine::Impl::readyInternal() const {
        return shared && shared->device && surface && swapchain && seqImage && seqLayers > 0;
    }

    void Engine::Impl::clearOutputWindowLocked() {
        outputBinding.invalidate();
        if (!shared || !shared->device) return;
        if (!surface && !window && !swapchain) return;
        if (narrowTeardown) {
            waitRetireAllFrames();
            // Rendering fences alone cannot retire presentation semaphore waits.
            QueueGuard lock(shared);
            check(vkQueueWaitIdle(shared->graphicsQueue), "outputQueueIdle");
        } else {
            vkDeviceWaitIdle(shared->device);
        }
        waitRetireAllFrames();
        destroySwapchain();
        if (surface) {
            vkDestroySurfaceKHR(shared->instance, surface, nullptr);
            surface = VK_NULL_HANDLE;
        }
        if (window) {
            ANativeWindow_release(window);
            window = nullptr;
        }
    }

    void Engine::Impl::destroy() {
        SharedDevice *owned = shared;
        if (!owned) return;
        // Release the queue lock before dropping the shared-device reference:
        // SharedDevice::release() may destroy the recursive_mutex that QueueGuard owns.
        // 先释放队列锁，再减少共享设备引用；release() 可能销毁 QueueGuard 持有的
        // recursive_mutex，不能在锁作用域内调用。
        {
            std::unique_lock<std::recursive_mutex> lock(owned->queueMutex, std::defer_lock);
            if (!narrowTeardown) lock.lock();
            if (owned->device) {
                if (!narrowTeardown) vkDeviceWaitIdle(owned->device);
                else waitRetireAllFrames();
                clearOutputWindowLocked();
                destroySequenceResources();
                if (colorModule) {
                    vkDestroyShaderModule(owned->device, colorModule, nullptr);
                    colorModule = VK_NULL_HANDLE;
                }
                if (renderPass) {
                    vkDestroyRenderPass(owned->device, renderPass, nullptr);
                    renderPass = VK_NULL_HANDLE;
                }
                swapFormat = VK_FORMAT_UNDEFINED;
                for (auto &slot : frames) {
                    destroyUploadBuffer(slot);
                    if (slot.imageAvailable) {
                        vkDestroySemaphore(owned->device, slot.imageAvailable, nullptr);
                        slot.imageAvailable = VK_NULL_HANDLE;
                    }
                    if (slot.renderFinished) {
                        vkDestroySemaphore(owned->device, slot.renderFinished, nullptr);
                        slot.renderFinished = VK_NULL_HANDLE;
                    }
                    if (slot.fence) {
                        vkDestroyFence(owned->device, slot.fence, nullptr);
                        slot.fence = VK_NULL_HANDLE;
                    }
                }
                if (cmdPool) {
                    vkDestroyCommandPool(owned->device, cmdPool, nullptr);
                    cmdPool = VK_NULL_HANDLE;
                }
                if (vertexBuffer) {
                    vkDestroyBuffer(owned->device, vertexBuffer, nullptr);
                    vertexBuffer = VK_NULL_HANDLE;
                }
                if (vertexMemory) {
                    vkFreeMemory(owned->device, vertexMemory, nullptr);
                    vertexMemory = VK_NULL_HANDLE;
                }
            }
        }
        shared = nullptr;
        SharedDevice::release();
    }

    Engine *Engine::create() {
        auto *engine = new Engine();
        engine->impl_ = new Impl();
        if (!engine->impl_->initialize()) {
            engine->destroy();
            return nullptr;
        }
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
        if (!impl_) return false;
        // The player serializes this engine. An unchanged successful binding needs
        // neither the process-wide queue lock nor a device-idle / WSI rebuild.
        if (impl_->outputBinding.canReuse(window, width, height,
                                          impl_->surface && impl_->swapchain)) {
            return true;
        }
        impl_->outputBinding.invalidate();
        if (!window) return false;
        std::unique_lock<std::recursive_mutex> lock(impl_->shared->queueMutex, std::defer_lock);
        if (!impl_->narrowTeardown) lock.lock();
        impl_->clearOutputWindowLocked();
        ANativeWindow_acquire(window);
        impl_->window = window;
        VkAndroidSurfaceCreateInfoKHR createInfo{
                .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
                .pNext = nullptr,
                .flags = 0,
                .window = window,
        };
        if (!check(vkCreateAndroidSurfaceKHR(impl_->shared->instance, &createInfo, nullptr,
                                             &impl_->surface), "androidSurface")) {
            ANativeWindow_release(window);
            impl_->window = nullptr;
            return false;
        }
        VkBool32 supported = VK_FALSE;
        vkGetPhysicalDeviceSurfaceSupportKHR(
                impl_->shared->physical, impl_->shared->graphicsQueueFamily, impl_->surface,
                &supported);
        if (!supported) {
            KTX_LOGE("queue family does not support present");
            impl_->clearOutputWindowLocked();
            return false;
        }
        if (!impl_->createSwapchain(width, height)) {
            impl_->clearOutputWindowLocked();
            return false;
        }
        impl_->outputBinding.recordSuccess(window, width, height);
        return true;
    }

    void Engine::clearOutputWindow() {
        if (!impl_) return;
        std::unique_lock<std::recursive_mutex> lock(impl_->shared->queueMutex, std::defer_lock);
        if (!impl_->narrowTeardown) lock.lock();
        impl_->clearOutputWindowLocked();
    }

    void Engine::setNarrowTeardown(bool enabled) {
        if (impl_) impl_->narrowTeardown = enabled;
    }

    bool Engine::setHueRows(const float *rows, size_t length) {
        if (!impl_ || !rows || length != 12 || impl_->seqImage) return false;
        std::memcpy(impl_->colorPush.rows, rows, sizeof(impl_->colorPush.rows));
        impl_->colorEnabled = true;
        return true;
    }

    bool Engine::setTextureArrayFile(uint32_t format, uint32_t width, uint32_t height,
                                     uint32_t layers, int fd, int64_t offset, size_t length) {
        return impl_ && impl_->setTextureArrayRaw(static_cast<VkFormat>(format), width,
                height, layers, nullptr, length, fd, offset);
    }

    bool Engine::setTextureArray(const uint8_t *data, size_t length) {
        Impl *i = pimpl();
        return i && i->setTextureArray(data, length);
    }

    bool Engine::setTextureArrayRaw(
            uint32_t format,
            uint32_t width,
            uint32_t height,
            uint32_t layers,
            const uint8_t *data,
            size_t length) {
        Impl *i = pimpl();
        return i && i->setTextureArrayRaw(
                static_cast<VkFormat>(format), width, height, layers, data, length);
    }

    bool Engine::setTextureArrayZstd(
            uint32_t format,
            uint32_t width,
            uint32_t height,
            uint32_t layers,
            const uint8_t *compressed,
            size_t compressedLength,
            size_t uncompressedLength) {
        Impl *i = pimpl();
        return i && i->setTextureArrayZstd(
                static_cast<VkFormat>(format), width, height, layers, compressed,
                compressedLength, uncompressedLength);
    }

    bool Engine::presentFrame(uint32_t layer) {
        Impl *i = pimpl();
        if (!i || !i->shared) return false;
        QueueGuard lock(i->shared);
        return i->present(layer);
    }

    bool Engine::presentFrameFile(uint32_t layer, int fd, int64_t offset, size_t length,
                                  int *ioError) {
        *ioError = 0;
        Impl *i = pimpl();
        if (!i || !i->shared) return false;
        return i->presentFile(layer, fd, offset, length, ioError);
    }

    bool Engine::ready() const {
        const Impl *i = pimpl();
        return i && i->readyInternal();
    }

    bool Engine::presentFrameBytes(uint32_t layer, const void *bytes, size_t length) {
        Impl *i = pimpl();
        if (!i || !i->shared || !bytes) return false;
        int ioError = 0;
        return i->presentFile(layer, -1, 0, length, &ioError, bytes);
    }

} // namespace kap_vk

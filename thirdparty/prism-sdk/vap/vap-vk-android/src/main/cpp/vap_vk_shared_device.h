#pragma once

#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>

#include "vap_queue_guard.h"

namespace vap_vk {

    /**
     * Process-wide Vulkan 1.1 device shared by all VAP sessions.
     * 由所有 VAP 会话共享的进程级 Vulkan 1.1 设备。
     */
    struct SharedDevice {
        VkInstance instance = VK_NULL_HANDLE;
        VkPhysicalDevice physical = VK_NULL_HANDLE;
        VkDevice device = VK_NULL_HANDLE;
        uint32_t graphicsQueueFamily = 0;
        VkQueue graphicsQueue = VK_NULL_HANDLE;
        VkShaderModule vertModule = VK_NULL_HANDLE;
        VkShaderModule fragModule = VK_NULL_HANDLE;
        // Vulkan queues require external synchronization across sessions for submit,
        // present, and device-idle waits.
        // Vulkan 队列要求跨会话对提交、呈现及设备空闲等待进行外部同步。
        std::recursive_mutex queueMutex;
        int refCount = 0;
        VkPhysicalDeviceMemoryProperties memoryProps{};
        bool memoryPropsReady = false;
        // When enabled, write descriptors while recording commands instead of calling
        // vkUpdateDescriptorSets each frame.
        // 启用后在录制命令时写入描述符，替代每帧调用 vkUpdateDescriptorSets。
        bool pushDescriptors = false;
        PFN_vkCmdPushDescriptorSetKHR fnPushDescriptor = nullptr;
        // Loaded once in init(); every engine session must read these from SharedDevice.
        // File-local copies in vap_vk_engine.cpp stay null after the SharedDevice split
        // and make presentAhb reject every frame.
        // 在 init() 里加载一次；每个引擎会话必须从 SharedDevice 读这些入口。
        // 拆分后 vap_vk_engine.cpp 里的文件局部副本一直为 null，会导致
        // presentAhb 拒绝每一帧。
        PFN_vkGetAndroidHardwareBufferPropertiesANDROID fnGetAhbProps = nullptr;
        PFN_vkCreateSamplerYcbcrConversion fnCreateYcbcr = nullptr;
        PFN_vkDestroySamplerYcbcrConversion fnDestroyYcbcr = nullptr;

        // Round-robin completion slots for the batched submit/present path.
        // 批量提交/呈现路径循环使用的完成槽位。
        static constexpr int kBatchSlots = 6;
        struct BatchSlot {
            VkFence fence = VK_NULL_HANDLE; // Initially signaled so first selection never blocks.
                                            // 初始为已触发，首次选取不会阻塞。
            VkSemaphore renderFinished = VK_NULL_HANDLE;
            int holds = 0; // Participant frame slots still dependent on this batch.
                           // 仍依赖本批次的参与帧槽位数量。
        };
        BatchSlot batchSlots[kBatchSlots]{};

        static SharedDevice *acquire();

        static void release();

        /**
         * Create the shared VkDevice ahead of the first engine and hold one reference
         * until the first engine consumes it. Moves vkCreateInstance/vkCreateDevice
         * and shader module creation off the first-frame critical path.
         * 在首个引擎之前创建共享 VkDevice 并持有一个引用，直到首个引擎消费。
         * 把 vkCreateInstance/vkCreateDevice 与 shader module 创建移出首帧关键路径。
         */
        static void prewarm();

        /**
         * Drop the prewarm reference (if any). Called by the first engine after it has
         * acquired its own reference.
         * 释放预热引用（若有）。由首个引擎在获取自己的引用后调用。
         */
        static void consumePrewarm();

        bool init();

        void shutdown();
    };

} // namespace vap_vk

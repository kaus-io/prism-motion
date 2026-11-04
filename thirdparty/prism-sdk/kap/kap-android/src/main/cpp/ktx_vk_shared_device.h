#pragma once

#include <vulkan/vulkan.h>

#include <mutex>

namespace kap_vk {

    /**
     * Process-wide Vulkan 1.1 device shared by all KTX animation engines.
     * 所有 KTX 动画引擎共享的进程级 Vulkan 1.1 设备。
     */
    struct SharedDevice {
        VkInstance instance = VK_NULL_HANDLE;
        VkPhysicalDevice physical = VK_NULL_HANDLE;
        VkDevice device = VK_NULL_HANDLE;
        uint32_t graphicsQueueFamily = 0;
        VkQueue graphicsQueue = VK_NULL_HANDLE;
        VkShaderModule vertModule = VK_NULL_HANDLE;
        VkShaderModule fragModule = VK_NULL_HANDLE;
        VkPhysicalDeviceMemoryProperties memoryProps{};
        bool astcSupported = false;
        bool etc2Supported = false;

        // Vulkan queues require external synchronization for submit / present / idle.
        // Vulkan 队列要求对提交 / 呈现 / 等待空闲做外部同步。
        std::recursive_mutex queueMutex;
        int refCount = 0;

        static SharedDevice *acquire();

        static void release();

        /** Create the device ahead of the first engine. / 在首个引擎前创建设备。 */
        static void prewarm();

        /** Drop the prewarm reference after the first engine acquires its own. / 首个引擎获取引用后释放预热引用。 */
        static void consumePrewarm();

        bool init();

        void shutdown();
    };

} // namespace kap_vk

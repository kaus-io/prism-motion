#include "ktx_vk_shared_device.h"

#include "shaders/ktx_seq_frag_spv.h"
#include "shaders/ktx_vert_spv.h"

#include <android/log.h>
#include <vulkan/vulkan.h>

#include <mutex>
#include <vector>

#define KTX_VK_LOG_TAG "KtxAnimVk"
#define KTX_LOGI(...) __android_log_print(ANDROID_LOG_INFO, KTX_VK_LOG_TAG, __VA_ARGS__)
#define KTX_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, KTX_VK_LOG_TAG, __VA_ARGS__)

namespace kap_vk {
    namespace {
        constexpr uint32_t kApiVersion = VK_API_VERSION_1_1;

        bool check(VkResult result, const char *what) {
            if (result == VK_SUCCESS) return true;
            KTX_LOGE("%s failed: %d", what, (int) result);
            return false;
        }

        VkShaderModule makeShaderModule(VkDevice device, const uint32_t *words, size_t count) {
            VkShaderModuleCreateInfo info{
                    .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
                    .pNext = nullptr,
                    .flags = 0,
                    .codeSize = count * sizeof(uint32_t),
                    .pCode = words,
            };
            VkShaderModule module = VK_NULL_HANDLE;
            if (vkCreateShaderModule(device, &info, nullptr, &module) != VK_SUCCESS) {
                return VK_NULL_HANDLE;
            }
            return module;
        }

        SharedDevice *gSharedDevice = nullptr;
        std::mutex gSharedDeviceMutex;
        std::mutex gPrewarmMutex;
        int gPrewarmRefs = 0;
    } // namespace

    bool SharedDevice::init() {
        uint32_t instanceApi = 0;
        vkEnumerateInstanceVersion(&instanceApi);
        if (instanceApi < kApiVersion) {
            KTX_LOGE("Instance Vulkan %u.%u < 1.1", VK_VERSION_MAJOR(instanceApi),
                     VK_VERSION_MINOR(instanceApi));
            return false;
        }

        const char *instanceExtensions[] = {
                VK_KHR_SURFACE_EXTENSION_NAME,
                VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
        };
        VkApplicationInfo app{
                .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                .pNext = nullptr,
                .pApplicationName = "kap",
                .applicationVersion = 1,
                .pEngineName = "kap",
                .engineVersion = 1,
                .apiVersion = kApiVersion,
        };
        VkInstanceCreateInfo instanceInfo{
                .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .pApplicationInfo = &app,
                .enabledLayerCount = 0,
                .ppEnabledLayerNames = nullptr,
                .enabledExtensionCount =
                        (uint32_t) (sizeof(instanceExtensions) / sizeof(instanceExtensions[0])),
                .ppEnabledExtensionNames = instanceExtensions,
        };
        if (!check(vkCreateInstance(&instanceInfo, nullptr, &instance), "vkCreateInstance")) {
            return false;
        }

        uint32_t count = 0;
        vkEnumeratePhysicalDevices(instance, &count, nullptr);
        if (count == 0) return false;
        std::vector<VkPhysicalDevice> devices(count);
        vkEnumeratePhysicalDevices(instance, &count, devices.data());
        bool found = false;
        for (auto physicalDevice : devices) {
            VkPhysicalDeviceProperties props{};
            vkGetPhysicalDeviceProperties(physicalDevice, &props);
            if (props.apiVersion < kApiVersion) continue;
            uint32_t queueCount = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueCount, nullptr);
            std::vector<VkQueueFamilyProperties> queues(queueCount);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &queueCount, queues.data());
            for (uint32_t index = 0; index < queueCount; index++) {
                if ((queues[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
                physical = physicalDevice;
                graphicsQueueFamily = index;
                KTX_LOGI("Shared VkDevice GPU: %s (api %u.%u)", props.deviceName,
                         VK_VERSION_MAJOR(props.apiVersion),
                         VK_VERSION_MINOR(props.apiVersion));
                found = true;
                break;
            }
            if (found) break;
        }
        if (!found) {
            KTX_LOGE("No Vulkan 1.1 graphics device");
            return false;
        }

        const char *deviceExtensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
        float priority = 1.f;
        VkDeviceQueueCreateInfo queueInfo{
                .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .queueFamilyIndex = graphicsQueueFamily,
                .queueCount = 1,
                .pQueuePriorities = &priority,
        };
        VkPhysicalDeviceFeatures supported{};
        vkGetPhysicalDeviceFeatures(physical, &supported);
        astcSupported = supported.textureCompressionASTC_LDR == VK_TRUE;
        etc2Supported = supported.textureCompressionETC2 == VK_TRUE;
        VkPhysicalDeviceFeatures enabled{};
        enabled.textureCompressionASTC_LDR =
                astcSupported ? VK_TRUE : VK_FALSE;
        enabled.textureCompressionETC2 = etc2Supported ? VK_TRUE : VK_FALSE;
        VkDeviceCreateInfo deviceInfo{
                .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .queueCreateInfoCount = 1,
                .pQueueCreateInfos = &queueInfo,
                .enabledLayerCount = 0,
                .ppEnabledLayerNames = nullptr,
                .enabledExtensionCount =
                        (uint32_t) (sizeof(deviceExtensions) / sizeof(deviceExtensions[0])),
                .ppEnabledExtensionNames = deviceExtensions,
                .pEnabledFeatures = &enabled,
        };
        if (!check(vkCreateDevice(physical, &deviceInfo, nullptr, &device), "vkCreateDevice")) {
            return false;
        }
        vkGetDeviceQueue(device, graphicsQueueFamily, 0, &graphicsQueue);

        vertModule = makeShaderModule(device, kap_vk_shaders::ktxVertSpv,
                                      kap_vk_shaders::ktxVertSpv_WORDS);
        fragModule = makeShaderModule(device, kap_vk_shaders::ktxSeqFragSpv,
                                      kap_vk_shaders::ktxSeqFragSpv_WORDS);
        if (!vertModule || !fragModule) return false;
        vkGetPhysicalDeviceMemoryProperties(physical, &memoryProps);
        KTX_LOGI("Shared VkDevice ready (ASTC=%d ETC2=%d)", (int) astcSupported,
                 (int) etc2Supported);
        return true;
    }

    void SharedDevice::shutdown() {
        if (device) vkDeviceWaitIdle(device);
        if (vertModule) vkDestroyShaderModule(device, vertModule, nullptr);
        if (fragModule) vkDestroyShaderModule(device, fragModule, nullptr);
        if (device) vkDestroyDevice(device, nullptr);
        if (instance) vkDestroyInstance(instance, nullptr);
        vertModule = VK_NULL_HANDLE;
        fragModule = VK_NULL_HANDLE;
        device = VK_NULL_HANDLE;
        instance = VK_NULL_HANDLE;
        physical = VK_NULL_HANDLE;
        graphicsQueue = VK_NULL_HANDLE;
        memoryProps = {};
        astcSupported = false;
        etc2Supported = false;
    }

    SharedDevice *SharedDevice::acquire() {
        std::lock_guard<std::mutex> lock(gSharedDeviceMutex);
        if (!gSharedDevice) {
            auto *created = new SharedDevice();
            if (!created->init()) {
                created->shutdown();
                delete created;
                return nullptr;
            }
            gSharedDevice = created;
        }
        gSharedDevice->refCount++;
        KTX_LOGI("Shared VkDevice acquire ref=%d", gSharedDevice->refCount);
        return gSharedDevice;
    }

    void SharedDevice::release() {
        std::lock_guard<std::mutex> lock(gSharedDeviceMutex);
        if (!gSharedDevice) return;
        gSharedDevice->refCount--;
        KTX_LOGI("Shared VkDevice release ref=%d", gSharedDevice->refCount);
        if (gSharedDevice->refCount <= 0) {
            gSharedDevice->shutdown();
            delete gSharedDevice;
            gSharedDevice = nullptr;
        }
    }

    void SharedDevice::prewarm() {
        {
            std::lock_guard<std::mutex> lock(gPrewarmMutex);
            if (gPrewarmRefs > 0) return;
        }
        if (acquire() == nullptr) return;
        bool already;
        {
            std::lock_guard<std::mutex> lock(gPrewarmMutex);
            already = gPrewarmRefs > 0;
            if (!already) gPrewarmRefs++;
        }
        if (already) {
            release();
            return;
        }
        KTX_LOGI("Shared VkDevice prewarmed");
    }

    void SharedDevice::consumePrewarm() {
        bool take;
        {
            std::lock_guard<std::mutex> lock(gPrewarmMutex);
            take = gPrewarmRefs > 0;
            if (take) gPrewarmRefs--;
        }
        if (take) release();
    }

} // namespace kap_vk

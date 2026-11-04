#include "vap_vk_shared_device.h"

#include "shaders/vap_frag_spv.h"
#include "shaders/vap_vert_spv.h"

#include <android/log.h>
#include <vulkan/vulkan.h>

#include <cstring>
#include <vector>

#define VAP_VK_LOG_TAG "VapVk"
#define VAP_LOGI(...) __android_log_print(ANDROID_LOG_INFO, VAP_VK_LOG_TAG, __VA_ARGS__)
#define VAP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, VAP_VK_LOG_TAG, __VA_ARGS__)

namespace vap_vk {
    namespace {

        constexpr uint32_t kApiVersion = VK_API_VERSION_1_1;
        // Adreno 643 / D2 measurements (12-way, 2026-07-18) show push descriptors regress
        // CPU by 8 points versus persistent sets: the driver emulates pushes with internal
        // descriptor sets, making each record costlier than vkUpdateDescriptorSets. Keep the
        // path for other GPUs, but disable it here.
        // Adreno 643 / D2 实测（12 路，2026-07-18）表明，相比持久描述符集，推送描述符
        // 令 CPU 指标回退 8 点：驱动以内建描述符集模拟推送，每次录制反而比
        // vkUpdateDescriptorSets 更昂贵。保留该路径供其他 GPU 复测，但此处禁用。
        constexpr bool kEnablePushDescriptors = false;

        bool check(VkResult r, const char *what) {
            if (r == VK_SUCCESS) return true;
            VAP_LOGE("%s failed: %d", what, (int) r);
            return false;
        }

        VkShaderModule makeShaderModule(VkDevice device, const uint32_t *words, size_t count) {
            VkShaderModuleCreateInfo ci{
                    .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
                    .pNext = nullptr,
                    .flags = 0,
                    .codeSize = count * sizeof(uint32_t),
                    .pCode = words,
            };
            VkShaderModule mod = VK_NULL_HANDLE;
            if (vkCreateShaderModule(device, &ci, nullptr, &mod) != VK_SUCCESS)
                return VK_NULL_HANDLE;
            return mod;
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
            VAP_LOGE("Instance Vulkan %u.%u < 1.1",
                     VK_VERSION_MAJOR(instanceApi), VK_VERSION_MINOR(instanceApi));
            return false;
        }

        const char *instExts[] = {
                VK_KHR_SURFACE_EXTENSION_NAME,
                VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
                VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME,
                VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME,
                VK_KHR_EXTERNAL_SEMAPHORE_CAPABILITIES_EXTENSION_NAME,
        };
        VkApplicationInfo app{
                .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                .pNext = nullptr,
                .pApplicationName = "vap",
                .applicationVersion = 1,
                .pEngineName = "vap",
                .engineVersion = 1,
                .apiVersion = kApiVersion,
        };
        VkInstanceCreateInfo ici{
                .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .pApplicationInfo = &app,
                .enabledLayerCount = 0,
                .ppEnabledLayerNames = nullptr,
                .enabledExtensionCount = (uint32_t) (sizeof(instExts) / sizeof(instExts[0])),
                .ppEnabledExtensionNames = instExts,
        };
        if (!check(vkCreateInstance(&ici, nullptr, &instance), "vkCreateInstance"))
            return false;

        uint32_t count = 0;
        vkEnumeratePhysicalDevices(instance, &count, nullptr);
        if (count == 0) return false;
        std::vector<VkPhysicalDevice> devices(count);
        vkEnumeratePhysicalDevices(instance, &count, devices.data());
        bool found = false;
        for (auto pd: devices) {
            VkPhysicalDeviceProperties props{};
            vkGetPhysicalDeviceProperties(pd, &props);
            if (props.apiVersion < kApiVersion) continue;
            uint32_t qCount = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(pd, &qCount, nullptr);
            std::vector<VkQueueFamilyProperties> qprops(qCount);
            vkGetPhysicalDeviceQueueFamilyProperties(pd, &qCount, qprops.data());
            for (uint32_t i = 0; i < qCount; i++) {
                if (qprops[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
                    physical = pd;
                    graphicsQueueFamily = i;
                    VAP_LOGI("Shared VkDevice GPU: %s (api %u.%u)", props.deviceName,
                             VK_VERSION_MAJOR(props.apiVersion),
                             VK_VERSION_MINOR(props.apiVersion));
                    found = true;
                    break;
                }
            }
            if (found) break;
        }
        if (!found) {
            VAP_LOGE("No Vulkan 1.1 graphics device");
            return false;
        }

        const char *baseDevExts[] = {
                VK_KHR_SWAPCHAIN_EXTENSION_NAME,
                VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
                VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME,
                VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME,
                VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
                VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME,
                VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
                VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
        };
        uint32_t extCount = 0;
        vkEnumerateDeviceExtensionProperties(physical, nullptr, &extCount, nullptr);
        std::vector<VkExtensionProperties> availExts(extCount);
        vkEnumerateDeviceExtensionProperties(physical, nullptr, &extCount,
                                             availExts.data());
        bool hasPushDescriptor = false;
        for (const auto &e: availExts) {
            if (kEnablePushDescriptors &&
                strcmp(e.extensionName, VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME) == 0) {
                hasPushDescriptor = true;
                break;
            }
        }
        std::vector<const char *> devExts(
                baseDevExts, baseDevExts + sizeof(baseDevExts) / sizeof(baseDevExts[0]));
        if (hasPushDescriptor) devExts.push_back(VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME);
        float prio = 1.f;
        VkDeviceQueueCreateInfo qci{
                .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .queueFamilyIndex = graphicsQueueFamily,
                .queueCount = 1,
                .pQueuePriorities = &prio,
        };
        VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcrFeatures{
                .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES,
                .pNext = nullptr,
                .samplerYcbcrConversion = VK_TRUE,
        };
        VkPhysicalDeviceFeatures2 features2{
                .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
                .pNext = &ycbcrFeatures,
                .features = {},
        };
        VkDeviceCreateInfo dci{
                .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                .pNext = &features2,
                .flags = 0,
                .queueCreateInfoCount = 1,
                .pQueueCreateInfos = &qci,
                .enabledLayerCount = 0,
                .ppEnabledLayerNames = nullptr,
                .enabledExtensionCount = (uint32_t) devExts.size(),
                .ppEnabledExtensionNames = devExts.data(),
                .pEnabledFeatures = nullptr,
        };
        if (!check(vkCreateDevice(physical, &dci, nullptr, &device), "vkCreateDevice"))
            return false;
        vkGetDeviceQueue(device, graphicsQueueFamily, 0, &graphicsQueue);

        pushDescriptors = hasPushDescriptor;
        if (pushDescriptors) {
            fnPushDescriptor = (PFN_vkCmdPushDescriptorSetKHR)
                    vkGetDeviceProcAddr(device, "vkCmdPushDescriptorSetKHR");
            pushDescriptors = fnPushDescriptor != nullptr;
        }
        VAP_LOGI("push descriptors: %s", pushDescriptors ? "on" : "off");

        fnGetAhbProps = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)
                vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID");
        fnCreateYcbcr = (PFN_vkCreateSamplerYcbcrConversion)
                vkGetDeviceProcAddr(device, "vkCreateSamplerYcbcrConversion");
        fnDestroyYcbcr = (PFN_vkDestroySamplerYcbcrConversion)
                vkGetDeviceProcAddr(device, "vkDestroySamplerYcbcrConversion");
        if (!fnGetAhbProps || !fnCreateYcbcr || !fnDestroyYcbcr) {
            VAP_LOGE("Missing AHB / YCbCr device entry points");
            return false;
        }

        vertModule = makeShaderModule(device, vap_vk_shaders::kVertSpv,
                                      vap_vk_shaders::kVertSpv_WORDS);
        fragModule = makeShaderModule(device, vap_vk_shaders::kFragSpv,
                                      vap_vk_shaders::kFragSpv_WORDS);
        if (!vertModule || !fragModule) return false;
        vkGetPhysicalDeviceMemoryProperties(physical, &memoryProps);
        memoryPropsReady = true;

        VkFenceCreateInfo bfci{
                .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
                .pNext = nullptr,
                .flags = VK_FENCE_CREATE_SIGNALED_BIT,
        };
        VkSemaphoreCreateInfo bsci{
                .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
        };
        for (auto &b: batchSlots) {
            if (!check(vkCreateFence(device, &bfci, nullptr, &b.fence), "batchFence") ||
                !check(vkCreateSemaphore(device, &bsci, nullptr, &b.renderFinished),
                       "batchSemaphore")) {
                return false;
            }
        }
        VAP_LOGI("Shared VkDevice ready");
        return true;
    }

    void SharedDevice::shutdown() {
        if (device) vkDeviceWaitIdle(device);
        for (auto &b: batchSlots) {
            if (b.fence) vkDestroyFence(device, b.fence, nullptr);
            if (b.renderFinished) vkDestroySemaphore(device, b.renderFinished, nullptr);
            b = {};
        }
        if (vertModule) vkDestroyShaderModule(device, vertModule, nullptr);
        if (fragModule) vkDestroyShaderModule(device, fragModule, nullptr);
        if (device) vkDestroyDevice(device, nullptr);
        if (instance) vkDestroyInstance(instance, nullptr);
        vertModule = fragModule = VK_NULL_HANDLE;
        device = VK_NULL_HANDLE;
        instance = VK_NULL_HANDLE;
        physical = VK_NULL_HANDLE;
        graphicsQueue = VK_NULL_HANDLE;
        memoryPropsReady = false;
        memoryProps = {};
        fnGetAhbProps = nullptr;
        fnCreateYcbcr = nullptr;
        fnDestroyYcbcr = nullptr;
    }

    SharedDevice *SharedDevice::acquire() {
        std::lock_guard<std::mutex> lock(gSharedDeviceMutex);
        if (!gSharedDevice) {
            auto *s = new SharedDevice();
            if (!s->init()) {
                s->shutdown();
                delete s;
                return nullptr;
            }
            gSharedDevice = s;
        }
        gSharedDevice->refCount++;
        VAP_LOGI("Shared VkDevice acquire ref=%d", gSharedDevice->refCount);
        return gSharedDevice;
    }

    void SharedDevice::release() {
        std::lock_guard<std::mutex> lock(gSharedDeviceMutex);
        if (!gSharedDevice) return;
        gSharedDevice->refCount--;
        VAP_LOGI("Shared VkDevice release ref=%d", gSharedDevice->refCount);
        if (gSharedDevice->refCount <= 0) {
            gSharedDevice->shutdown();
            delete gSharedDevice;
            gSharedDevice = nullptr;
        }
    }

    void SharedDevice::prewarm() {
        {
            std::lock_guard<std::mutex> warmLock(gPrewarmMutex);
            if (gPrewarmRefs > 0) return;
        }
        if (acquire() == nullptr) return;
        bool extra;
        {
            std::lock_guard<std::mutex> warmLock(gPrewarmMutex);
            extra = gPrewarmRefs > 0;
            if (!extra) gPrewarmRefs++;
        }
        if (extra) {
            // Another prewarm won the race; drop the extra reference.
            // 另一个预热先完成；释放本线程多拿的引用。
            release();
            return;
        }
        VAP_LOGI("Shared VkDevice prewarmed");
    }

    void SharedDevice::consumePrewarm() {
        bool take;
        {
            std::lock_guard<std::mutex> warmLock(gPrewarmMutex);
            take = gPrewarmRefs > 0;
            if (take) gPrewarmRefs--;
        }
        if (take) release();
    }

} // namespace vap_vk

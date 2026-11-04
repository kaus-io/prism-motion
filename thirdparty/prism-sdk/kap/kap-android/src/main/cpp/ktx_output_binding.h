#pragma once

namespace kap_vk {

// Instance-owned; the player serializes access. The engine retains the bound
// ANativeWindow, so a replacement native window cannot reuse its live identity.
// Track the requested size, not the extent chosen/clamped by the Vulkan driver.
class OutputBinding {
public:
    bool canReuse(const void *window, int width, int height, bool resourcesReady) const {
        return resourcesReady && window && window_ == window &&
               width_ == width && height_ == height;
    }

    void recordSuccess(const void *window, int width, int height) {
        window_ = window;
        width_ = width;
        height_ = height;
    }

    void invalidate() {
        window_ = nullptr;
        width_ = 0;
        height_ = 0;
    }

private:
    const void *window_ = nullptr;
    int width_ = 0;
    int height_ = 0;
};

} // namespace kap_vk

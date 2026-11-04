#pragma once
#include <mutex>

namespace kap_vk {
// Maintenance serializes resource and queue ownership. Render work tries the same
// lock and returns NotReady instead of waiting behind legacy WSI teardown.
struct QueueGuard {
    std::unique_lock<std::recursive_mutex> lock;
    template<class Shared>
    explicit QueueGuard(Shared *shared) : lock() {
        if (shared) lock = std::unique_lock<std::recursive_mutex>(shared->queueMutex);
    }
};
struct TryQueueGuard {
    std::unique_lock<std::recursive_mutex> lock;
    template<class Shared>
    explicit TryQueueGuard(Shared *shared) : lock(shared->queueMutex, std::try_to_lock) {}
    explicit operator bool() const { return lock.owns_lock(); }
};
}

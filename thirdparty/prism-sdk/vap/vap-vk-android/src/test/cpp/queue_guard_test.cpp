#include "vap_queue_guard.h"
#include <cassert>
#include <future>
#include <thread>

struct Shared { std::recursive_mutex queueMutex; };
int main() {
    Shared shared;
    std::promise<void> locked, release;
    auto unlocked = release.get_future();
    std::thread maintenance([&] {
        vap_vk::QueueGuard guard(&shared);
        // Native maintenance may call another queue operation on its own thread.
        vap_vk::QueueGuard nested(&shared);
        locked.set_value();
        unlocked.wait();
    });
    locked.get_future().wait();
    // This must return immediately while maintenance still owns the queue. A
    // blocking lock would deadlock this test before release can be signaled.
    {
        vap_vk::TryQueueGuard render(&shared);
        assert(!render);
    }
    release.set_value();
    maintenance.join();
    vap_vk::TryQueueGuard render(&shared);
    assert(render);
}

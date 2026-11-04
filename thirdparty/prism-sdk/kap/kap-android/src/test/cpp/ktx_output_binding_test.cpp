// Host: c++ -std=c++17 -Wall -Wextra -pedantic ktx_output_binding_test.cpp
//       -o output_binding_test && ./output_binding_test
#include "../../main/cpp/ktx_output_binding.h"

#include <cassert>
#include <cstdio>

int main() {
    kap_vk::OutputBinding binding;
    int firstWindow = 0;
    int replacementWindow = 0;
    assert(!binding.canReuse(&firstWindow, 170, 170, true));
    assert(!binding.canReuse(nullptr, 0, 0, true));

    // A successful native binding is idempotent only for the retained identity
    // and the exact requested size, with real surface/swapchain resources alive.
    binding.recordSuccess(&firstWindow, 170, 170);
    assert(binding.canReuse(&firstWindow, 170, 170, true));
    assert(!binding.canReuse(&firstWindow, 170, 170, false));
    assert(!binding.canReuse(&firstWindow, 171, 170, true));
    assert(!binding.canReuse(&firstWindow, 170, 171, true));
    assert(!binding.canReuse(nullptr, 170, 170, true));
    // Reusing a Java wrapper cannot reuse the still-retained native identity.
    assert(!binding.canReuse(&replacementWindow, 170, 170, true));

    // Store requested dimensions rather than a driver-clamped swapchain extent.
    binding.recordSuccess(&firstWindow, 1, 1);
    assert(binding.canReuse(&firstWindow, 1, 1, true));
    assert(!binding.canReuse(&firstWindow, 64, 64, true));

    // Clear, swapchain destruction, or a WSI error invalidates success even when
    // the native resource handles have not yet been cleared.
    binding.invalidate();
    assert(!binding.canReuse(&firstWindow, 1, 1, true));
    binding.recordSuccess(&firstWindow, 170, 170);
    binding.invalidate(); // A failed rebind must not retain the previous key.
    assert(!binding.canReuse(&firstWindow, 170, 170, true));
    assert(!binding.canReuse(&replacementWindow, 170, 170, true));

    binding.recordSuccess(&replacementWindow, 170, 170);
    assert(binding.canReuse(&replacementWindow, 170, 170, true));
    assert(!binding.canReuse(&firstWindow, 170, 170, true));
    binding.invalidate();
    binding.invalidate(); // Clear/close can be repeated safely.
    assert(!binding.canReuse(&replacementWindow, 170, 170, true));
    std::puts("output binding: identity, requested size, readiness and invalidation passed");
}

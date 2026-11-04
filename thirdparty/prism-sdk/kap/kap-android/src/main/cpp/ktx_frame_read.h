#pragma once

#include <cstddef>
#include <cstdint>

namespace kap {
// Returns errno, or EIO for EOF. Partial data must never be submitted to the GPU.
int readFrameAt(int fd, int64_t offset, void *destination, size_t length);
}

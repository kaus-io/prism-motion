#pragma once

#include <cstdint>

namespace kap {
// Bounded-memory decode. Descriptors are borrowed; the caller owns publication
// and removal of partial output. Cancellation is checked between decoder steps.
bool decodeZstdToFile(int inputFd, int64_t offset, int64_t compressedLength,
                      int outputFd, int64_t decodedLength,
                      bool (*cancelled)(void *) = nullptr, void *context = nullptr,
                      int64_t prefixLength = 0);
}

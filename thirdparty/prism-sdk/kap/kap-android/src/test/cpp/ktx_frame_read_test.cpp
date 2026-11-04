// Host: c++ -std=c++17 -D_LARGEFILE64_SOURCE ktx_frame_read_test.cpp
//       ../../main/cpp/ktx_frame_read.cpp -Wl,--wrap=pread64 -o frame_read_test
#include "../../main/cpp/ktx_frame_read.h"

#include <algorithm>
#include <array>
#include <cassert>
#include <cerrno>
#include <cstdio>
#include <cstdint>
#include <limits>
#include <unistd.h>

namespace {
bool shortReads = false;
bool interruptRead = false;
}

extern "C" ssize_t __real_pread64(int, void *, size_t, off64_t);
extern "C" ssize_t __wrap_pread64(int fd, void *dst, size_t size, off64_t offset) {
    if (interruptRead) { errno = EINTR; return -1; }
    return __real_pread64(fd, dst, shortReads ? std::min(size, size_t{7}) : size, offset);
}

int main() {
    FILE *file = std::tmpfile();
    assert(file);
    std::array<uint8_t, 512> input{};
    for (size_t i = 0; i < input.size(); ++i) input[i] = static_cast<uint8_t>(i * 17);
    assert(std::fwrite(input.data(), 1, input.size(), file) == input.size());
    assert(std::fflush(file) == 0);
    const int fd = fileno(file);
    std::array<uint8_t, 121> output{};
    // Nonzero APK/asset base + frame offset; pread must not change the fd cursor.
    assert(lseek(fd, 13, SEEK_SET) == 13);
    assert(kap::readFrameAt(fd, 173, output.data(), output.size()) == 0);
    assert(std::equal(output.begin(), output.end(), input.begin() + 173));
    assert(lseek(fd, 0, SEEK_CUR) == 13);
    shortReads = true;
    assert(kap::readFrameAt(fd, 9, output.data(), output.size()) == 0);
    assert(std::equal(output.begin(), output.end(), input.begin() + 9));
    shortReads = false;
    assert(kap::readFrameAt(fd, 500, output.data(), output.size()) == EIO);
    assert(kap::readFrameAt(fd, 512, output.data(), output.size()) == EIO);
    assert(kap::readFrameAt(-1, 0, output.data(), output.size()) == EINVAL);
    assert(kap::readFrameAt(fd, -1, output.data(), output.size()) == EINVAL);
    assert(kap::readFrameAt(fd, std::numeric_limits<int64_t>::max(), output.data(), 1) == EINVAL);
    interruptRead = true;
    assert(kap::readFrameAt(fd, 0, output.data(), output.size()) == EINTR);
    interruptRead = false;
    // Large sparse offsets must work on arm32 as well as arm64 (pread64).
    constexpr int64_t largeOffset = (int64_t{1} << 32) + 31;
    assert(pwrite64(fd, input.data(), input.size(), largeOffset) == static_cast<ssize_t>(input.size()));
    assert(kap::readFrameAt(fd, largeOffset + 173, output.data(), output.size()) == 0);
    assert(std::equal(output.begin(), output.end(), input.begin() + 173));
    std::fclose(file);
    std::puts("frame reads: offsets, short reads, EOF, invalid ranges, EINTR, 64-bit offsets passed");
}

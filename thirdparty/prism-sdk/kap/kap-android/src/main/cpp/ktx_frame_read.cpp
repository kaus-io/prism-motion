#include "ktx_frame_read.h"

#include <cerrno>
#include <limits>
#include <unistd.h>

namespace kap {
int readFrameAt(int fd, int64_t offset, void *destination, size_t length) {
    if (fd < 0 || !destination || offset < 0 ||
        length > static_cast<uint64_t>(std::numeric_limits<int64_t>::max() - offset)) {
        return EINVAL;
    }
    size_t done = 0;
    while (done < length) {
        const ssize_t count = pread64(fd, static_cast<uint8_t *>(destination) + done,
                                     length - done, offset + static_cast<int64_t>(done));
        // Do not loop forever on interruption; surface cancellation must be able
        // to unwind. JNI propagates this as InterruptedIOException.
        if (count < 0) return errno;
        if (count == 0) return EIO;
        done += static_cast<size_t>(count);
    }
    return 0;
}
}

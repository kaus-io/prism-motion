#include "ktx_zstd_file.h"

#include <zstd.h>
#include <algorithm>
#include <cerrno>
#include <limits>
#include <memory>
#include <unistd.h>
#include <vector>

namespace kap {
bool decodeZstdToFile(int inputFd, int64_t offset, int64_t compressedLength,
                      int outputFd, int64_t decodedLength,
                      bool (*cancelled)(void *), void *context, int64_t prefixLength) {
    if (inputFd < 0 || outputFd < 0 || offset < 0 || compressedLength <= 0 ||
        decodedLength <= 0 || prefixLength < 0 || prefixLength > decodedLength ||
        offset > std::numeric_limits<int64_t>::max() - compressedLength) {
        return false;
    }
    std::unique_ptr<ZSTD_DStream, decltype(&ZSTD_freeDStream)> stream(
            ZSTD_createDStream(), ZSTD_freeDStream);
    if (!stream || ZSTD_isError(ZSTD_initDStream(stream.get())) ||
        ZSTD_isError(ZSTD_DCtx_setParameter(stream.get(), ZSTD_d_windowLogMax, 25))) {
        return false;
    }
    // At most a 32 MiB decoder window plus two ~128 KiB I/O buffers,
    // independent of the total decoded animation size.
    std::vector<char> input(ZSTD_DStreamInSize());
    std::vector<char> output(ZSTD_DStreamOutSize());
    int64_t consumed = 0;
    int64_t written = 0;
    size_t remaining = 1;
    while (consumed < compressedLength) {
        if (cancelled && cancelled(context)) return false;
        const size_t count = static_cast<size_t>(std::min<int64_t>(
                compressedLength - consumed, static_cast<int64_t>(input.size())));
        ssize_t read;
        do {
            read = pread64(inputFd, input.data(), count, offset + consumed);
        } while (read < 0 && errno == EINTR && !(cancelled && cancelled(context)));
        if (read <= 0) return false;
        consumed += read;
        ZSTD_inBuffer in{input.data(), static_cast<size_t>(read), 0};
        bool drain = false;
        do {
            if (cancelled && cancelled(context)) return false;
            const size_t capacity = prefixLength > 0
                    ? static_cast<size_t>(std::min<int64_t>(output.size(), prefixLength - written))
                    : output.size();
            ZSTD_outBuffer out{output.data(), capacity, 0};
            const size_t before = in.pos;
            remaining = ZSTD_decompressStream(stream.get(), &out, &in);
            if (ZSTD_isError(remaining) || out.pos > static_cast<uint64_t>(decodedLength - written)) {
                return false;
            }
            size_t position = 0;
            while (position < out.pos) {
                if (cancelled && cancelled(context)) return false;
                const ssize_t result = write(outputFd, output.data() + position, out.pos - position);
                if (result < 0 && errno == EINTR) continue;
                if (result <= 0) return false;
                position += static_cast<size_t>(result);
            }
            written += static_cast<int64_t>(out.pos);
            // This is a private preview, never a published cache entry. The
            // ordinary full decode must still validate size and checksum later.
            if (prefixLength > 0 && written == prefixLength) return true;
            drain = out.pos == out.size && remaining != 0;
            if (before == in.pos && out.pos == 0 && in.pos < in.size) return false;
        } while (in.pos < in.size || drain);
    }
    return remaining == 0 && written == decodedLength;
}
}

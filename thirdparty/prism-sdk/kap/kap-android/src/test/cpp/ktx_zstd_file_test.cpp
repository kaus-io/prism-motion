#include "ktx_zstd_file.h"
#include <zstd.h>
#include <algorithm>
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include <unistd.h>
#include <sys/stat.h>
#include <vector>

struct CloseFile {
    void operator()(FILE *file) const { if (file) fclose(file); }
};
using File = std::unique_ptr<FILE, CloseFile>;

static File temporary(const std::string &directory) {
    std::string name = directory + "/ktx-zstd-test-XXXXXX";
    const int fd = mkstemp(name.data());
    assert(fd >= 0);
    unlink(name.c_str());
    return File(fdopen(fd, "w+b"));
}

int main(int argc, char **argv) {
    assert(argc >= 2);
    auto input = temporary(argv[1]);
    auto output = temporary(argv[1]);
    std::vector<char> raw(4 * 1024 * 1024 + 17);
    uint32_t value = 42;
    for (auto &byte : raw) {
        value = value * 1664525u + 1013904223u;
        byte = static_cast<char>(value >> 24);
    }
    std::vector<char> compressed(ZSTD_compressBound(raw.size()));
    const size_t size = ZSTD_compress(compressed.data(), compressed.size(), raw.data(), raw.size(), 3);
    assert(!ZSTD_isError(size));
    const std::string prefix = "asset-container-prefix";
    assert(fwrite(prefix.data(), 1, prefix.size(), input.get()) == prefix.size());
    assert(fwrite(compressed.data(), 1, size, input.get()) == size);
    fflush(input.get());
    auto decode = [&](int64_t stored, int64_t decoded) {
        assert(ftruncate(fileno(output.get()), 0) == 0);
        rewind(output.get());
        return kap::decodeZstdToFile(fileno(input.get()), prefix.size(), stored,
                                        fileno(output.get()), decoded);
    };
    assert(decode(size, raw.size()));
    rewind(output.get());
    std::vector<char> actual(raw.size());
    assert(fread(actual.data(), 1, actual.size(), output.get()) == actual.size());
    assert(actual == raw);
    for (int64_t length : {int64_t{1}, int64_t{46656}, int64_t{121104}, int64_t{3875328}}) {
        assert(ftruncate(fileno(output.get()), 0) == 0);
        rewind(output.get());
        assert(lseek(fileno(output.get()), 0, SEEK_SET) == 0);
        assert(kap::decodeZstdToFile(fileno(input.get()), prefix.size(), size,
                fileno(output.get()), raw.size(), nullptr, nullptr, length));
        struct stat status{};
        assert(fstat(fileno(output.get()), &status) == 0 && status.st_size == length);
        rewind(output.get());
        std::vector<char> preview(length);
        assert(fread(preview.data(), 1, preview.size(), output.get()) == preview.size());
        assert(std::equal(preview.begin(), preview.end(), raw.begin()));
    }
    assert(!kap::decodeZstdToFile(fileno(input.get()), prefix.size(), size,
            fileno(output.get()), raw.size(), nullptr, nullptr, raw.size() + 1));
    assert(!decode(size - 1, raw.size()));
    assert(!decode(size, raw.size() - 1));
    assert(!decode(size, raw.size() + 1));
    assert(!decode(size + 1, raw.size()));
    int checks = 0;
    rewind(output.get());
    assert(!kap::decodeZstdToFile(fileno(input.get()), prefix.size(), size,
            fileno(output.get()), raw.size(), [](void *value) {
                return ++*static_cast<int *>(value) > 3;
            }, &checks));
    assert(!kap::decodeZstdToFile(-1, 0, size, fileno(output.get()), raw.size()));
    // Highly compressible data exercises draining buffered output after input ends.
    raw.assign(raw.size(), 'a');
    const size_t smallSize = ZSTD_compress(compressed.data(), compressed.size(), raw.data(), raw.size(), 3);
    assert(!ZSTD_isError(smallSize));
    assert(ftruncate(fileno(input.get()), 0) == 0);
    rewind(input.get());
    assert(fwrite(prefix.data(), 1, prefix.size(), input.get()) == prefix.size());
    assert(fwrite(compressed.data(), 1, smallSize, input.get()) == smallSize);
    fflush(input.get());
    assert(decode(smallSize, raw.size()));
    rewind(output.get());
    assert(fread(actual.data(), 1, actual.size(), output.get()) == actual.size());
    assert(actual == raw);
    puts("Zstd file decode: roundtrip, offset, truncation, size, cancellation and output draining passed");
    for (int index = 2; index < argc; ++index) {
        File asset(fopen(argv[index], "rb"));
        assert(asset);
        unsigned char header[104];
        assert(fread(header, 1, sizeof(header), asset.get()) == sizeof(header));
        auto u64 = [&](size_t offset) {
            uint64_t result = 0;
            for (size_t i = 0; i < 8; ++i) result |= uint64_t(header[offset + i]) << (8 * i);
            return result;
        };
        const uint64_t offset = u64(80), stored = u64(88), decoded = u64(96);
        assert(ftruncate(fileno(output.get()), 0) == 0);
        rewind(output.get());
        assert(kap::decodeZstdToFile(fileno(asset.get()), offset, stored,
                                        fileno(output.get()), decoded));
        std::vector<char> packed(stored), expected(decoded), streamed(decoded);
        assert(fseeko(asset.get(), offset, SEEK_SET) == 0);
        assert(fread(packed.data(), 1, stored, asset.get()) == stored);
        assert(ZSTD_decompress(expected.data(), decoded, packed.data(), stored) == decoded);
        rewind(output.get());
        assert(fread(streamed.data(), 1, decoded, output.get()) == decoded);
        assert(streamed == expected);
        const int64_t previewLength = std::min<uint64_t>(decoded, 121104);
        assert(ftruncate(fileno(output.get()), 0) == 0);
        rewind(output.get());
        assert(kap::decodeZstdToFile(fileno(asset.get()), offset, stored,
                fileno(output.get()), decoded, nullptr, nullptr, previewLength));
        rewind(output.get());
        std::vector<char> preview(previewLength);
        assert(fread(preview.data(), 1, preview.size(), output.get()) == preview.size());
        assert(std::equal(preview.begin(), preview.end(), expected.begin()));
        printf("%s: streamed content matches one-shot decode\n", argv[index]);
    }
}

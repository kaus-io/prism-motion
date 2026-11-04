#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <unordered_map>

namespace vap_vk {
// Owns only imports whose GPU fences have completed. In-flight owners stay outside
// the pool; old codec generations are destroyed when those owners eventually retire.
template<class Import, size_t Capacity>
class RetiredImportPool {
    static_assert(Capacity > 0);
    struct Entry { Import value; uint64_t stamp; };
    std::unordered_map<uintptr_t, Entry> entries;
    uint64_t currentGeneration = 0;
    uint64_t clock = 0;
public:
    uint64_t generation() const { return currentGeneration; }
    size_t size() const { return entries.size(); }

    bool take(uintptr_t key, Import &out) {
        auto it = entries.find(key);
        if (it == entries.end()) return false;
        out = it->second.value;
        entries.erase(it);
        return true;
    }

    template<class Destroy>
    void recycle(uintptr_t key, Import &value, Destroy destroy) {
        if (value.generation != currentGeneration || entries.find(key) != entries.end()) {
            destroy(value);
            return;
        }
        if (entries.size() >= Capacity) {
            auto oldest = std::min_element(entries.begin(), entries.end(),
                    [](const auto &a, const auto &b) { return a.second.stamp < b.second.stamp; });
            destroy(oldest->second.value);
            entries.erase(oldest);
        }
        entries.emplace(key, Entry{value, ++clock});
        value = {};
    }

    template<class Destroy>
    void clear(Destroy destroy) {
        for (auto &entry : entries) destroy(entry.second.value);
        entries.clear();
    }

    template<class Destroy>
    void resetGeneration(Destroy destroy) {
        ++currentGeneration;
        clear(destroy);
    }
};
}

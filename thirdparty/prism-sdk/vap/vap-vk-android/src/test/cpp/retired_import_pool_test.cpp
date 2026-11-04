#include "vap_retired_import_pool.h"
#include <cassert>
#include <unordered_map>

struct Import { int id = 0; uint64_t generation = 0; };
int main() {
    vap_vk::RetiredImportPool<Import, 2> pool;
    std::unordered_map<int, int> destroyed;
    auto destroy = [&](Import &value) { assert(value.id); ++destroyed[value.id]; value = {}; };
    Import a{1, pool.generation()}, b{2, pool.generation()}, c{3, pool.generation()};
    pool.recycle(1, a, destroy);
    pool.recycle(2, b, destroy);
    assert(!a.id && !b.id && pool.size() == 2);
    // Reusing 1 must make 2 the victim, rather than rejecting new buffer 3.
    assert(pool.take(1, a));
    pool.recycle(1, a, destroy);
    pool.recycle(3, c, destroy);
    assert(destroyed[2] == 1 && destroyed[1] == 0 && destroyed[3] == 0);
    assert(!pool.take(2, b));
    Import duplicate{4, pool.generation()};
    pool.recycle(3, duplicate, destroy);
    assert(destroyed[4] == 1 && pool.size() == 2);
    // A frame may outlive codec reset. Reset clears only retired entries; the
    // still-in-flight owner must be released (not recached) after its GPU fence.
    assert(pool.take(1, a));
    pool.resetGeneration(destroy);
    assert(destroyed[3] == 1 && destroyed[1] == 0 && pool.size() == 0);
    pool.recycle(1, a, destroy);
    assert(destroyed[1] == 1 && pool.size() == 0);
    Import fresh{5, pool.generation()};
    pool.recycle(5, fresh, destroy);
    assert(pool.take(5, fresh));
    destroy(fresh);
    pool.clear(destroy);
    for (int id = 1; id <= 5; ++id) assert(destroyed[id] == 1);
}

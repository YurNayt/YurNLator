// YurNLator: readdir side of the duplicate-folder fix (see dirdedup_getdents.cpp).
#include <dirent.h>
#include <dlfcn.h>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>

#define EXPORT __attribute__((visibility("default"))) extern "C"

static std::mutex g_lock;
static std::unordered_map<DIR*, std::unordered_set<std::string>> g_seen;

template <typename Ent>
static Ent* dedup(DIR* dir, Ent* (*real)(DIR*)) {
    for (;;) {
        Ent* e = real(dir);
        if (!e) {
            std::lock_guard<std::mutex> lk(g_lock);
            g_seen.erase(dir);
            return e;
        }
        std::lock_guard<std::mutex> lk(g_lock);
        if (g_seen[dir].insert(std::string(e->d_name)).second) return e;
    }
}

EXPORT struct dirent* readdir(DIR* dir) {
    static auto real = (struct dirent* (*)(DIR*))dlsym(RTLD_NEXT, "readdir");
    return real ? dedup<struct dirent>(dir, real) : nullptr;
}

EXPORT struct dirent64* readdir64(DIR* dir) {
    static auto real = (struct dirent64* (*)(DIR*))dlsym(RTLD_NEXT, "readdir64");
    return real ? dedup<struct dirent64>(dir, real) : nullptr;
}

EXPORT void rewinddir(DIR* dir) {
    static auto real = (void (*)(DIR*))dlsym(RTLD_NEXT, "rewinddir");
    { std::lock_guard<std::mutex> lk(g_lock); g_seen.erase(dir); }
    if (real) real(dir);
}

EXPORT int closedir(DIR* dir) {
    static auto real = (int (*)(DIR*))dlsym(RTLD_NEXT, "closedir");
    { std::lock_guard<std::mutex> lk(g_lock); g_seen.erase(dir); }
    return real ? real(dir) : -1;
}

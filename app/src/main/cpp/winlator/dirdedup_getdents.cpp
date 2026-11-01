// YurNLator: on Android's shared storage (FUSE), Wine sees every FOLDER twice in a listing
// (files once). Directory entry names are unique by definition, so repeated names within one
// listing pass are dropped here. Preloaded into Wine processes with the other hooks (fakeinput).
//
// This file deliberately doesn't include <dirent.h>, so the getdents64 prototype can't clash
// with whatever the headers declare.
#include <dlfcn.h>
#include <unistd.h>
#include <stdint.h>
#include <string.h>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>

#define EXPORT __attribute__((visibility("default"))) extern "C"

struct yurn_dirent64 {
    uint64_t d_ino;
    int64_t  d_off;
    unsigned short d_reclen;
    unsigned char  d_type;
    char d_name[];
};

static std::mutex g_lock;
static std::unordered_map<int, std::unordered_set<std::string>> g_seen;

typedef int (*getdents64_fn)(unsigned int, void*, unsigned int);

EXPORT int getdents64(unsigned int fd, void* buf, unsigned int count) {
    static getdents64_fn real = (getdents64_fn)dlsym(RTLD_NEXT, "getdents64");
    if (!real) return -1;
    // A new pass starts at offset 0.
    bool fresh = lseek((int)fd, 0, SEEK_CUR) == 0;
    int n = real(fd, buf, count);
    if (n <= 0) {
        if (n == 0) { std::lock_guard<std::mutex> lk(g_lock); g_seen.erase((int)fd); }
        return n;
    }
    std::lock_guard<std::mutex> lk(g_lock);
    auto& seen = g_seen[(int)fd];
    if (fresh) seen.clear();
    char* src = (char*)buf;
    char* end = src + n;
    char* dst = src;
    while (src < end) {
        auto* d = (yurn_dirent64*)src;
        unsigned short len = d->d_reclen;
        if (len == 0) break;
        bool keep = seen.insert(std::string(d->d_name)).second;
        if (keep) {
            if (dst != src) memmove(dst, src, len);
            dst += len;
        }
        src += len;
    }
    int kept = (int)(dst - (char*)buf);
    // Everything in this chunk was a repeat: read on rather than report the end too early.
    if (kept == 0) return getdents64(fd, buf, count);
    return kept;
}

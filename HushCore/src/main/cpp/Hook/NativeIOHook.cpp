//
// NativeIOHook.cpp
//
// 容器方案里最关键的一块：native 层的文件系统重定向。
//
// 背景：这套引擎原本只在 Java 层做重定向（替换 java.io.UnixFileSystem 的方法）。
// 但加固壳（网易易盾 libsecsdk/libBootloader、Unity 的 IL2CPP）全都在 C 层直接
// 调 open/openat/fopen，Java 层 hook 对它们完全无效。壳子去读
// /data/data/<被多开的应用>/... 拿到 EACCES/ENOENT，就直接 _exit 静默退出，
// 表现就是"点开闪一下就没了"，日志里连异常都没有。
//
// 这里用 Dobby 在 libc 的符号上做 inline hook，把路径换成容器内的真实路径。
// 纯 C 字符串替换，不回调 Java —— 这条路上每秒可能几千次调用，走 JNI 会死。
//

#include <jni.h>

#include <atomic>
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <unistd.h>

#include <IO.h>

#include "Log.h"
#include "DiagSink.h"
#include "Dobby/dobby.h"

#define NIO_PATH_MAX 2048

namespace {

typedef int (*open_t)(const char *, int, ...);
typedef int (*open_fix_t)(const char *, int);
typedef int (*openat_t)(int, const char *, int, ...);
typedef int (*openat_fix_t)(int, const char *, int);
typedef FILE *(*fopen_t)(const char *, const char *);
typedef int (*access_t)(const char *, int);
typedef int (*faccessat_t)(int, const char *, int, int);
typedef int (*stat_t)(const char *, void *);
typedef int (*statat_t)(int, const char *, void *, int);
typedef int (*statx_t)(int, const char *, int, unsigned int, void *);
typedef ssize_t (*readlink_t)(const char *, char *, size_t);
typedef ssize_t (*readlinkat_t)(int, const char *, char *, size_t);
typedef DIR *(*opendir_t)(const char *);
typedef int (*mkdir_t)(const char *, mode_t);
typedef int (*mkdirat_t)(int, const char *, mode_t);
typedef int (*unlink_t)(const char *);
typedef int (*unlinkat_t)(int, const char *, int);
typedef int (*rmdir_t)(const char *);
typedef int (*rename_t)(const char *, const char *);
typedef int (*renameat_t)(int, const char *, int, const char *);
typedef int (*truncate_t)(const char *, off_t);
typedef int (*chmod_t)(const char *, mode_t);

// 内存写入探针：模块拿到 main 解析出的地址后，究竟有没有真的往游戏进程里写。
// 这条链路完全在 native（libdrmcore 之类）里，Java 层 hook 看不到，
// 只能在容器进程的 libc 出口上截。
typedef ssize_t (*pwrite_t)(int, const void *, size_t, off_t);
typedef ssize_t (*pwrite64_t)(int, const void *, size_t, off64_t);
typedef ssize_t (*process_vm_writev_t)(pid_t, const struct iovec *, unsigned long,
                                       const struct iovec *, unsigned long, unsigned long);

// ── 原始函数指针（Dobby trampoline）────────────────────────────────────
//
// 坑：open / open64 / __open_2 是三个不同语义的符号，绝不能共用一个槽位。
// __open_2 是 bionic 的 FORTIFY 版本（签名里没有 mode 参数），它的职责就是
// 「flags 带 O_CREAT 时直接 fatal」。一旦把它的跳板写进 g_open，之后任何
// 带 O_CREAT 的 open 都会走到它里面，当场 abort：
//     FORTIFY: open: called with O_CREAT/O_TMPFILE but no mode
// 表现为进程被 SIGABRT 杀掉，看起来跟加固壳检测退出一模一样。
// 所以每个符号一个跳板、一个包装函数。
open_t g_open = nullptr;
open_t g_open64 = nullptr;
open_fix_t g_open_2 = nullptr;

openat_t g_openat = nullptr;
openat_t g_openat64 = nullptr;
openat_fix_t g_openat_2 = nullptr;

fopen_t g_fopen = nullptr;
access_t g_access = nullptr;
faccessat_t g_faccessat = nullptr;
stat_t g_stat = nullptr;
stat_t g_lstat = nullptr;
statat_t g_fstatat = nullptr;
statx_t g_statx = nullptr;
readlink_t g_readlink = nullptr;
readlinkat_t g_readlinkat = nullptr;
opendir_t g_opendir = nullptr;
mkdir_t g_mkdir = nullptr;
mkdirat_t g_mkdirat = nullptr;
unlink_t g_unlink = nullptr;
unlinkat_t g_unlinkat = nullptr;
rmdir_t g_rmdir = nullptr;
rename_t g_rename = nullptr;
renameat_t g_renameat = nullptr;
truncate_t g_truncate = nullptr;
chmod_t g_chmod = nullptr;


std::atomic<bool> g_on(false);

/**
 * 重入深度。
 *
 * 注意这里的语义：只有在「正在执行 libc 原始实现」期间才 > 0。
 * 之前写成 DepthGuard 在调用 tr() 之前构造，导致 tr() 里 `g_depth != 0`
 * 恒成立 —— hook 装上了、也被调用了，但每一次都原样放行，等于没做重定向
 * （日志表现：native libc hook active=true，但 probe open 依然 errno 13）。
 * 现在改成：先算路径，再进深度保护，只保护真正下发的那一次系统调用。
 */
__thread int g_depth = 0;

struct DepthGuard {
    DepthGuard() { ++g_depth; }

    ~DepthGuard() { --g_depth; }
};

// ── 重定向路径的父目录兜底 ────────────────────────────────────────────
//
// 真机上的壳（爱加密的 code_cache/i11111i111.zip、乐固的 .legu 目录等）
// 都是「先写到自己的 code_cache，再从那里 DexClassLoader 加载」。
// 容器的沙盒目录只预建了 files/cache/databases/shared_prefs 这几个，
// code_cache 不存在时 open(O_CREAT) 直接返回 ENOENT，壳写失败又不检查，
// 接着去读同一个文件拿到 -1，把它当 dex 指针用 —— 表现就是
// 「页面出来了，然后闪退」。日志里那一行是直接证据：
//     IO access .../com.sky.zll/code_cache/i11111i111.zip rc=-1
//     SEGV-null addr=0x0
//
// 所以在重定向后的路径带 O_CREAT 时，把父目录补出来。每个父目录只做一次，
// 命中缓存直接返回，避免每次 open 都走一轮 syscall。
namespace {

constexpr int kDirCacheSlots = 256;
char g_dirCache[kDirCacheSlots][NIO_PATH_MAX];
std::atomic<uint32_t> g_dirSeq{0};

inline uint32_t fnv1a(const char *s) {
    uint32_t h = 2166136261u;
    while (*s) {
        h ^= (uint8_t) *s++;
        h *= 16777619u;
    }
    return h;
}

void mkdirsUpTo(const char *full) {
    char tmp[NIO_PATH_MAX];
    size_t n = strlen(full);
    if (n == 0 || n >= NIO_PATH_MAX) return;
    memcpy(tmp, full, n + 1);
    for (size_t i = 1; i < n; i++) {
        if (tmp[i] != '/') continue;
        tmp[i] = 0;
        ::mkdir(tmp, 0771);
        tmp[i] = '/';
    }
}

/** 确保 path 的父目录存在。只处理容器内的绝对路径。 */
void ensureParent(const char *path) {
    if (path == nullptr) return;
    const char *slash = strrchr(path, '/');
    if (slash == nullptr || slash == path) return;
    size_t len = (size_t) (slash - path);
    if (len >= NIO_PATH_MAX) return;
    // 只对容器沙盒内的路径动手，系统目录一个都不碰。
    if (strncmp(path, "/data/", 6) != 0 && strncmp(path, "/storage/", 9) != 0) return;

    char parent[NIO_PATH_MAX];
    memcpy(parent, path, len);
    parent[len] = 0;

    uint32_t h = fnv1a(parent);
    for (int i = 0; i < kDirCacheSlots; i++) {
        if (g_dirCache[i][0] != 0 && fnv1a(g_dirCache[i]) == h &&
            strcmp(g_dirCache[i], parent) == 0) {
            return;
        }
    }
    mkdirsUpTo(parent);
    uint32_t slot = g_dirSeq.fetch_add(1, std::memory_order_relaxed) & (kDirCacheSlots - 1);
    strncpy(g_dirCache[slot], parent, NIO_PATH_MAX - 1);
    g_dirCache[slot][NIO_PATH_MAX - 1] = 0;
}

} // namespace

/**
 * 前 N 次文件访问写进 diag.log。
 * 壳死之前到底在找哪个文件，是判断"它要什么"的唯一直接证据。
 * 有预算上限，避免刷爆日志把前面的上下文挤掉。
 */
std::atomic<int> g_traceOk(40);     // 命中规则的正常访问（能看出它在读哪些沙盒目录）
std::atomic<int> g_traceFail(80);   // 失败访问（能看出它缺哪个文件）

/**
 * ART 在 makeApplication 阶段会密集探测 dalvik-cache / oat / vdex / odex，
 * 一次启动就是十几条，全是 ENOENT 且毫无信息量，却能把后面真正的崩溃现场
 * 挤出消息长度。这里直接不记。
 */
inline bool ioNoise(const char *p) {
    if (p == nullptr) return false;
    if (strstr(p, "dalvik-cache") != nullptr) return true;
    if (strstr(p, "/oat/") != nullptr) return true;
    if (strstr(p, ".vdex") != nullptr) return true;
    if (strstr(p, ".odex") != nullptr) return true;
    if (strstr(p, ".dm") != nullptr) return true;
    return false;
}

inline void traceIo(const char *kind, const char *orig, const char *now, long rc) {
    if (!DiagSink::ready()) return;
    if (ioNoise(orig)) return;
    std::atomic<int> &bucket = (rc < 0) ? g_traceFail : g_traceOk;
    int left = bucket.load(std::memory_order_relaxed);
    if (left <= 0) return;
    bucket.store(left - 1, std::memory_order_relaxed);
    if (now != orig) {
        DiagSink::write("IO %s %s => %s rc=%ld", kind, orig, now, rc);
    } else {
        DiagSink::write("IO %s %s rc=%ld", kind, orig, rc);
    }
}

/**
 * /proc 访问探针。
 *
 * <p>跨 App 联动的辅助工具通常靠扫 /proc/&lt;pid&gt;/cmdline 找目标进程，容器里
 * 看到的是 com.hush.space:pN，于是永远判「游戏没运行」。这里只记录、不改行为，
 * 用来确认对方到底走的是哪条路；配额独立，不会被普通 IO 日志挤掉。
 */

// ── 游戏内存写入探针 ──────────────────────────────────────────────────
//
// 模块把 main 的输出直接交给 native（libdrmcore）处理，Java 层完全不知道
// 地址去了哪。要判断「功能没效果」到底是「没写」还是「写了没生效」，
// 只能在容器进程这一侧截 pwrite / process_vm_writev。
/** 命中规则就返回改写后的路径（写进 buf），否则返回原路径。 */
inline const char *tr(const char *path, char *buf) {
    if (path == nullptr || path[0] != '/')
        return path;
    if (!g_on.load(std::memory_order_relaxed) || g_depth != 0)
        return path;
    return IO::redirectPathTo(path, buf, NIO_PATH_MAX) ? buf : path;
}

// ── 各个 hook 实现 ────────────────────────────────────────────────────

/** 只有 O_CREAT / O_TMPFILE 时才真的存在 mode 这个可变参数。 */
#define NIO_TAKE_MODE(flags, mode)                                                    \
    do {                                                                              \
        if (((flags) & O_CREAT) || (((flags) & O_TMPFILE) == O_TMPFILE)) {             \
            va_list ap;                                                               \
            va_start(ap, flags);                                                      \
            (mode) = (mode_t) va_arg(ap, int);                                         \
            va_end(ap);                                                               \
        }                                                                             \
    } while (0)

int my_open(const char *path, int flags, ...) {
    mode_t mode = 0;
    NIO_TAKE_MODE(flags, mode);
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    int r;
    {
        DepthGuard guard;
        if (p != path && (flags & O_CREAT)) ensureParent(p);
        // 多传一个 mode 对 varargs 版本无害：bionic 只在 O_CREAT 时才去取它
        r = g_open ? g_open(p, flags, mode) : (int) syscall(__NR_openat, AT_FDCWD, p, flags, mode);
    }
    if (p != path || r < 0) traceIo("open", path ? path : "(null)", p, r);
    return r;
}

int my_open64(const char *path, int flags, ...) {
    mode_t mode = 0;
    NIO_TAKE_MODE(flags, mode);
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    int r;
    {
        DepthGuard guard;
        if (p != path && (flags & O_CREAT)) ensureParent(p);
        r = g_open64 ? g_open64(p, flags, mode)
                     : (int) syscall(__NR_openat, AT_FDCWD, p, flags, mode);
    }
    if (p != path || r < 0) traceIo("open64", path ? path : "(null)", p, r);
    return r;
}

/** __open_2(path, flags)：FORTIFY 版本，签名里没有 mode，必须原样只传两个参数。 */
int my_open_2(const char *path, int flags) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    int r;
    {
        DepthGuard guard;
        if (p != path && (flags & O_CREAT)) ensureParent(p);
        r = g_open_2 ? g_open_2(p, flags) : (int) syscall(__NR_openat, AT_FDCWD, p, flags, 0);
    }
    if (p != path || r < 0) traceIo("open_2", path ? path : "(null)", p, r);
    return r;
}

int my_openat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    NIO_TAKE_MODE(flags, mode);
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    int r;
    {
        DepthGuard guard;
        if (p != path && (flags & O_CREAT)) ensureParent(p);
        r = g_openat ? g_openat(dirfd, p, flags, mode)
                     : (int) syscall(__NR_openat, dirfd, p, flags, mode);
    }
    if (p != path || r < 0) traceIo("openat", path ? path : "(null)", p, r);
    return r;
}

int my_openat64(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    NIO_TAKE_MODE(flags, mode);
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    int r;
    {
        DepthGuard guard;
        if (p != path && (flags & O_CREAT)) ensureParent(p);
        r = g_openat64 ? g_openat64(dirfd, p, flags, mode)
                       : (int) syscall(__NR_openat, dirfd, p, flags, mode);
    }
    if (p != path || r < 0) traceIo("openat64", path ? path : "(null)", p, r);
    return r;
}

/** __openat_2(fd, path, flags)：同样是 FORTIFY 版本，只有三个参数。 */
int my_openat_2(int dirfd, const char *path, int flags) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    int r;
    {
        DepthGuard guard;
        if (p != path && (flags & O_CREAT)) ensureParent(p);
        r = g_openat_2 ? g_openat_2(dirfd, p, flags)
                       : (int) syscall(__NR_openat, dirfd, p, flags, 0);
    }
    if (p != path || r < 0) traceIo("openat_2", path ? path : "(null)", p, r);
    return r;
}

FILE *my_fopen(const char *path, const char *mode) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    FILE *f;
    {
        DepthGuard guard;
        f = g_fopen(p, mode);
    }
    if (p != path || f == nullptr) traceIo("fopen", path ? path : "(null)", p, f ? 0 : -1);
    return f;
}

int my_access(const char *path, int mode) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    int r;
    {
        DepthGuard guard;
        r = g_access(p, mode);
    }
    if (p != path || r != 0) traceIo("access", path ? path : "(null)", p, r);
    return r;
}

int my_faccessat(int dirfd, const char *path, int mode, int flags) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    DepthGuard guard;
    return g_faccessat(dirfd, p, mode, flags);
}

int my_stat(const char *path, void *st) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    int r;
    {
        DepthGuard guard;
        r = g_stat(p, st);
    }
    if (p != path || r != 0) traceIo("stat", path ? path : "(null)", p, r);
    return r;
}

int my_lstat(const char *path, void *st) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    DepthGuard guard;
    return g_lstat(p, st);
}

int my_fstatat(int dirfd, const char *path, void *st, int flags) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    DepthGuard guard;
    return g_fstatat(dirfd, p, st, flags);
}

int my_statx(int dirfd, const char *path, int flags, unsigned int mask, void *stx) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    DepthGuard guard;
    return g_statx(dirfd, p, flags, mask, stx);
}

ssize_t my_readlink(const char *path, char *out, size_t size) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    DepthGuard guard;
    return g_readlink(p, out, size);
}

ssize_t my_readlinkat(int dirfd, const char *path, char *out, size_t size) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    DepthGuard guard;
    return g_readlinkat(dirfd, p, out, size);
}

DIR *my_opendir(const char *path) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    DepthGuard guard;
    return g_opendir(p);
}

int my_mkdir(const char *path, mode_t mode) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    DepthGuard guard;
    return g_mkdir(p, mode);
}

int my_mkdirat(int dirfd, const char *path, mode_t mode) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    DepthGuard guard;
    return g_mkdirat(dirfd, p, mode);
}

int my_unlink(const char *path) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    DepthGuard guard;
    return g_unlink(p);
}

int my_unlinkat(int dirfd, const char *path, int flags) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    DepthGuard guard;
    return g_unlinkat(dirfd, p, flags);
}

int my_rmdir(const char *path) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    DepthGuard guard;
    return g_rmdir(p);
}

int my_rename(const char *oldPath, const char *newPath) {
    char b1[NIO_PATH_MAX];
    char b2[NIO_PATH_MAX];
    const char *a = tr(oldPath, b1);
    const char *b = tr(newPath, b2);
    DepthGuard guard;
    return g_rename(a, b);
}

int my_renameat(int oldDir, const char *oldPath, int newDir, const char *newPath) {
    char b1[NIO_PATH_MAX];
    char b2[NIO_PATH_MAX];
    const char *a = tr(oldPath, b1);
    const char *b = tr(newPath, b2);
    DepthGuard guard;
    return g_renameat(oldDir, a, newDir, b);
}

int my_truncate(const char *path, off_t length) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    DepthGuard guard;
    return g_truncate(p, length);
}

int my_chmod(const char *path, mode_t mode) {
    char buf[NIO_PATH_MAX];
    const char *p = tr(path, buf);
    DepthGuard guard;
    return g_chmod(p, mode);
}

// ── 安装逻辑 ──────────────────────────────────────────────────────────

struct SymRecord {
    const char *name;
    void *addr;
    int ret;      // DobbyHook 返回值
    bool hit;     // 是否真的挂上了
};

SymRecord g_syms[40];
int g_symCount = 0;

// Dobby 对同一地址重复 hook 会出错，open/open64 在 LP64 下往往是同一个符号。
void *g_hooked[40];
int g_hookedCount = 0;

// Dobby 的 arm64 补丁固定占 4 条指令（16 字节）。libc 里有几个函数挨得极近，
// 例如 renameat 与 rmdir 只隔 12 字节：先 hook rmdir 写了 16 字节，
// 再 hook renameat 的 16 字节会回头把 rmdir 的头 4 字节覆盖成 .quad 的字面量，
// 之后任何 rmdir 调用都会在函数入口执行非法指令 → SIGILL(4)，且 si_addr 正好是
// rmdir 的地址。这里按区间判重，宁可少挂一个符号，也不能踩坏邻居。
void *g_hookedEnd[40];

bool overlapsHooked(void *addr) {
    uintptr_t a = (uintptr_t) addr;
    for (int i = 0; i < g_hookedCount; i++) {
        uintptr_t s = (uintptr_t) g_hooked[i];
        uintptr_t e = (uintptr_t) g_hookedEnd[i];
        if (a < e && (a + 16) > s)
            return true;
    }
    return false;
}

/** 返回 true 表示这次真的挂上了。 */
bool hookOne(void *handle, const char *name, void *replace, void **originSlot) {
    void *addr = dlsym(handle, name);
    if (addr == nullptr) {
        // 符号不存在（不同 API 版本差异），不算失败
        return false;
    }
    if (overlapsHooked(addr)) {
        // 同一地址（open 与 open64 是同一实现）或者与已挂补丁区间重叠（renameat/rmdir）。
        ALOGD("hook %s skipped: overlaps %p", name, addr);
        return false;
    }
    void *origin = nullptr;
    int r = DobbyHook(addr, replace, &origin);
    bool ok = (r == 0 && origin != nullptr);
    if (!ok) {
        ALOGE("hook %s failed, ret=%d", name, r);
    } else {
        if (g_hookedCount < (int) (sizeof(g_hooked) / sizeof(g_hooked[0]))) {
            g_hooked[g_hookedCount] = addr;
            g_hookedEnd[g_hookedCount] = (void *) ((uintptr_t) addr + 16);
            g_hookedCount++;
        }
        *originSlot = origin;
    }
    if (g_symCount < (int) (sizeof(g_syms) / sizeof(g_syms[0]))) {
        g_syms[g_symCount].name = name;
        g_syms[g_symCount].addr = addr;
        g_syms[g_symCount].ret = r;
        g_syms[g_symCount].hit = ok;
        g_symCount++;
    }
    return ok;
}

}  // namespace

namespace NativeIO {

/**
 * 在 enableIO 之后调用。此时路径规则已经全部下发，可以安全地开始改写。
 * 幂等：重复调用直接返回。
 */
void install() {
    if (g_on.load())
        return;

    void *handle = dlopen("libc.so", RTLD_NOW);
    if (handle == nullptr) {
        ALOGE("dlopen libc.so failed");
        return;
    }

    int ok = 0;
    // 顺序有意义：先拿原始指针，再逐个改写。改写过程中若触发懒绑定，
    // 因为 g_on 还是 false，tr() 一律放行，不会走进未初始化状态。

    // 这些是主路径，任何一个挂上就说明 Dobby 在这台机器上是可用的
    if (hookOne(handle, "open", (void *) my_open, (void **) &g_open)) ok++;
    hookOne(handle, "open64", (void *) my_open64, (void **) &g_open64);
    hookOne(handle, "__open_2", (void *) my_open_2, (void **) &g_open_2);
    hookOne(handle, "__openat", (void *) my_openat, (void **) &g_openat);
    if (hookOne(handle, "openat", (void *) my_openat, (void **) &g_openat)) ok++;
    hookOne(handle, "openat64", (void *) my_openat64, (void **) &g_openat64);
    hookOne(handle, "__openat_2", (void *) my_openat_2, (void **) &g_openat_2);

    if (hookOne(handle, "fopen", (void *) my_fopen, (void **) &g_fopen)) ok++;
    hookOne(handle, "fopen64", (void *) my_fopen, (void **) &g_fopen);
    if (hookOne(handle, "access", (void *) my_access, (void **) &g_access)) ok++;
    hookOne(handle, "faccessat", (void *) my_faccessat, (void **) &g_faccessat);
    if (hookOne(handle, "stat", (void *) my_stat, (void **) &g_stat)) ok++;
    hookOne(handle, "stat64", (void *) my_stat, (void **) &g_stat);
    if (hookOne(handle, "lstat", (void *) my_lstat, (void **) &g_lstat)) ok++;
    hookOne(handle, "lstat64", (void *) my_lstat, (void **) &g_lstat);
    hookOne(handle, "fstatat", (void *) my_fstatat, (void **) &g_fstatat);
    hookOne(handle, "fstatat64", (void *) my_fstatat, (void **) &g_fstatat);
    hookOne(handle, "statx", (void *) my_statx, (void **) &g_statx);
    hookOne(handle, "readlink", (void *) my_readlink, (void **) &g_readlink);
    hookOne(handle, "readlinkat", (void *) my_readlinkat, (void **) &g_readlinkat);
    hookOne(handle, "opendir", (void *) my_opendir, (void **) &g_opendir);

    hookOne(handle, "mkdir", (void *) my_mkdir, (void **) &g_mkdir);
    hookOne(handle, "mkdirat", (void *) my_mkdirat, (void **) &g_mkdirat);
    hookOne(handle, "unlink", (void *) my_unlink, (void **) &g_unlink);
    hookOne(handle, "unlinkat", (void *) my_unlinkat, (void **) &g_unlinkat);
    hookOne(handle, "rmdir", (void *) my_rmdir, (void **) &g_rmdir);
    hookOne(handle, "rename", (void *) my_rename, (void **) &g_rename);
    hookOne(handle, "renameat", (void *) my_renameat, (void **) &g_renameat);
    hookOne(handle, "truncate", (void *) my_truncate, (void **) &g_truncate);
    hookOne(handle, "chmod", (void *) my_chmod, (void **) &g_chmod);


    // 只要主路径（open 系列）挂上了才算真正可用；其它符号挂了也不算数
    bool core = (g_open != nullptr) || (g_openat != nullptr);
    g_on.store(core);
    ALOGD("native io hook installed=%d core=%d rules=%zu", ok, (int) core, IO::ruleCount());
    DiagSink::write("IO-HOOK installed=%d core=%d rules=%zu", ok, (int) core, IO::ruleCount());
}

bool enabled() {
    return g_on.load();
}

/**
 * 把这一层的真实状态拼成文本交给 Java 侧写进 diag.log。
 * native 的 ALOGD 只进 logcat，用户导出的日志里看不到，所以必须从 JNI 回传。
 */
const char *diag(const char *path) {
    static char out[4096];
    int n = 0;
    n += snprintf(out + n, sizeof(out) - n, "on=%d rules=%zu syms=%d",
                  (int) g_on.load(), IO::ruleCount(), g_symCount);
    for (int i = 0; i < g_symCount && n < (int) sizeof(out) - 96; i++) {
        n += snprintf(out + n, sizeof(out) - n, "\n  %-12s %s ret=%d %p",
                      g_syms[i].name, g_syms[i].hit ? "OK " : "MISS", g_syms[i].ret,
                      g_syms[i].addr);
    }
    if (path != nullptr) {
        char buf[NIO_PATH_MAX];
        bool hit = IO::redirectPathTo(path, buf, NIO_PATH_MAX);
        n += snprintf(out + n, sizeof(out) - n, "\n  rule(%s) -> %s", path,
                      hit ? buf : "(no match)");
        // 再走一遍 open 的真实调用链，确认改写是否生效
        int fd = ::open(path, O_RDONLY | O_CLOEXEC);
        int err = errno;
        if (fd >= 0) ::close(fd);
        n += snprintf(out + n, sizeof(out) - n, "\n  hopen -> %s", fd >= 0 ? "ok" : "err");
        if (fd < 0)
            n += snprintf(out + n, sizeof(out) - n, " %d(%s)", err, strerror(err));
    }
    return out;
}

}  // namespace NativeIO

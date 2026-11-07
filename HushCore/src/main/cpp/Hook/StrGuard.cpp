//
// StrGuard.cpp
//

#include "StrGuard.h"

#include <atomic>
#include <cstdint>
#include <cstring>

#include <dlfcn.h>

#include "Log.h"
#include "DiagSink.h"
#include "ExitTrace.h"
#include "Dobby/dobby.h"

namespace {

typedef size_t (*strlen_t)(const char *);
typedef size_t (*strnlen_t)(const char *, size_t);
typedef int (*strcmp_t)(const char *, const char *);
typedef int (*strncmp_t)(const char *, const char *, size_t);

strlen_t g_strlen = nullptr;
strnlen_t g_strnlen = nullptr;
strcmp_t g_strcmp = nullptr;
strncmp_t g_strncmp = nullptr;

std::atomic<bool> g_on(false);
std::atomic<int> g_logs(6);     // 最多打 6 次现场，避免刷爆
__thread int g_depth = 0;       // 记录现场时自身触发的字符串调用不能再进来

void *g_hooked[4];
int g_hookedCount = 0;

/**
 * 是不是「一看就不可能」的指针。
 *
 * 用户态真实字符串都在 16MB 以上、内核半区以下 —— 两次比较直接放行，
 * 热路径几乎没有额外开销。
 * 其余（null / 低地址 / 内核区）再问一次映射：真能读就还是放行，
 * 读不了才算坏。这样既不会误伤 ART 堆里的合法字符串（0xb400...），
 * 也不会放过这次的 0xfffff9。
 */
inline bool suspect(const void *p) {
    uintptr_t v = (uintptr_t) p;
    if (v >= (uintptr_t) 0x01000000 && v < (uintptr_t) 0x0000800000000000ULL)
        return false;
    if (v == 0) return true;
    return !ExitTrace::safeToRead(p, 1);
}

/** 记录一次现场：谁传了什么、以及完整调用栈（正常上下文，比信号里那份准）。 */
void report(const char *api, const void *p, void *ra) {
    if (g_logs.fetch_sub(1) <= 0) return;
    g_depth = 1;
    char who[192];
    ExitTrace::describe(ra, who, sizeof(who));
    DiagSink::write("STR-BAD %s(%p) caller=%s -> treated as \"\"", api, p, who);
    ExitTrace::dumpStack("STR-BT");
    g_depth = 0;
}

/** 可疑就报一次；调用方按「空串」处理。 */
inline bool bad(const char *p, const char *api, void *ra) {
    if (p == nullptr) return true;
    if (!suspect(p)) return false;
    if (g_depth == 0) report(api, p, ra);
    return true;
}

size_t my_strlen(const char *s) {
    if (!g_on.load(std::memory_order_relaxed)) return g_strlen(s);
    if (bad(s, "strlen", __builtin_return_address(0))) return 0;
    return g_strlen(s);
}

size_t my_strnlen(const char *s, size_t n) {
    if (!g_on.load(std::memory_order_relaxed)) return g_strnlen(s, n);
    if (bad(s, "strnlen", __builtin_return_address(0))) return 0;
    return g_strnlen(s, n);
}

int my_strcmp(const char *a, const char *b) {
    if (!g_on.load(std::memory_order_relaxed)) return g_strcmp(a, b);
    void *ra = __builtin_return_address(0);
    const char *x = bad(a, "strcmp", ra) ? "" : a;
    const char *y = bad(b, "strcmp", ra) ? "" : b;
    if (x == a && y == b) return g_strcmp(a, b);
    return g_strcmp(x, y);
}

int my_strncmp(const char *a, const char *b, size_t n) {
    if (!g_on.load(std::memory_order_relaxed)) return g_strncmp(a, b, n);
    void *ra = __builtin_return_address(0);
    const char *x = bad(a, "strncmp", ra) ? "" : a;
    const char *y = bad(b, "strncmp", ra) ? "" : b;
    if (x == a && y == b) return g_strncmp(a, b, n);
    return g_strncmp(x, y, n);
}

void hookSym(void *handle, const char *name, void *replace, void **originSlot) {
    void *addr = dlsym(handle, name);
    if (addr == nullptr) return;
    for (int i = 0; i < g_hookedCount; i++)
        if (g_hooked[i] == addr) return;
    void *origin = nullptr;
    if (DobbyHook(addr, replace, &origin) != 0 || origin == nullptr) {
        ALOGE("str hook %s failed", name);
        return;
    }
    if (g_hookedCount < (int) (sizeof(g_hooked) / sizeof(g_hooked[0])))
        g_hooked[g_hookedCount++] = addr;
    *originSlot = origin;
}

}  // namespace

namespace StrGuard {

void install() {
    if (g_on.load()) return;

    void *handle = dlopen("libc.so", RTLD_NOW);
    if (handle == nullptr) {
        ALOGE("dlopen libc.so failed");
        return;
    }

    // 只挂「拿到坏指针就必死」的四个。memcpy / strcpy 太热，不碰。
    hookSym(handle, "strlen", (void *) my_strlen, (void **) &g_strlen);
    hookSym(handle, "strnlen", (void *) my_strnlen, (void **) &g_strnlen);
    hookSym(handle, "strcmp", (void *) my_strcmp, (void **) &g_strcmp);
    hookSym(handle, "strncmp", (void *) my_strncmp, (void **) &g_strncmp);

    // strlen 挂上才算可用：它是这次崩溃的落点
    g_on.store(g_strlen != nullptr);

    DiagSink::write("STR-GUARD strlen=%d strnlen=%d strcmp=%d strncmp=%d",
                    g_strlen != nullptr, g_strnlen != nullptr,
                    g_strcmp != nullptr, g_strncmp != nullptr);
}

}  // namespace StrGuard

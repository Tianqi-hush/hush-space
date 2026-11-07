//
// DiagSink.cpp
//
// 引擎 native 层统一的诊断输出通道。
// 只持有一个在「IO 重定向生效之前」打开的 fd，之后所有 native 埋点都直接
// write() 到它上面 —— 不走 open，因此不会被自己的 hook 拦截，也不会递归。
//

#include "DiagSink.h"

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <ctime>

#include <fcntl.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <unistd.h>

#if defined(__linux__)
#include <sys/prctl.h>
#endif

namespace {

int g_fd = -1;

/**
 * 早期缓冲。
 *
 * DiagSink 在 enableIO 里才真正 open，而 JNI_OnLoad（HushHook 初始化）比它早。
 * 之前那条 "HushHook init=0" 就是这样丢掉的 —— 唯一能说明失败原因的记录没了。
 * 这里先把还没落地的话攒着，open 成功后补写，标 [pre] 表示是早于日志开启时产生的。
 */
constexpr int kPendingMax = 24;
char g_pending[kPendingMax][256];
int g_pendingCount = 0;

void pushPending(const char *msg) {
    if (g_pendingCount >= kPendingMax) {
        // 满了就丢掉最旧的一条，保证最后几条（最贴近故障点）一定留下
        for (int i = 1; i < kPendingMax; i++) {
            memcpy(g_pending[i - 1], g_pending[i], sizeof(g_pending[0]));
        }
        g_pendingCount = kPendingMax - 1;
    }
    strncpy(g_pending[g_pendingCount], msg, sizeof(g_pending[0]) - 1);
    g_pending[g_pendingCount][sizeof(g_pending[0]) - 1] = '\0';
    g_pendingCount++;
}

/** 把 epoch 秒换成 HH:mm:ss.mmm，避免 localtime 在信号上下文里分配内存。 */
void stamp(char *out, size_t cap) {
    struct timeval tv{};
    gettimeofday(&tv, nullptr);
    long ms = tv.tv_sec * 1000L + tv.tv_usec / 1000L;
    // 设备是东八区，直接按 UTC+8 换算，避免 tzset 依赖环境变量
    long local = ms + 8L * 3600L * 1000L;
    long day = local % (24L * 3600L * 1000L);
    snprintf(out, cap, "%02ld:%02ld:%02ld.%03ld", day / 3600000L, (day / 60000L) % 60,
             (day / 1000L) % 60, day % 1000L);
}

}  // namespace

namespace DiagSink {

void open(const char *path) {
    if (g_fd >= 0 || path == nullptr || path[0] == '\0') return;
    g_fd = ::open(path, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0644);
    if (g_fd < 0) return;
    // 补写早期缓冲
    for (int i = 0; i < g_pendingCount; i++) {
        char line[300];
        int n = snprintf(line, sizeof(line), "[pre] %s\n", g_pending[i]);
        if (n > 0) {
            ssize_t ignored = ::write(g_fd, line, (size_t) n);
            (void) ignored;
        }
    }
    g_pendingCount = 0;
}

int fd() {
    return g_fd;
}

void write(const char *fmt, ...) {
    char msg[220];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);

    if (g_fd < 0) {
        pushPending(msg);
        return;
    }

    char line[1024];
    char ts[24];
    stamp(ts, sizeof(ts));

    char tname[24] = {0};
#if defined(__linux__)
    prctl(PR_GET_NAME, tname, 0, 0, 0);
#endif
    char name[20] = {0};
    if (tname[0] != '\0') {
        // 只取前 14 个字符，线程名太长会把行撑爆
        strncpy(name, tname, sizeof(name) - 1);
        name[sizeof(name) - 1] = '\0';
    }

    int n = snprintf(line, sizeof(line), "%s %d/%d %s: ", ts, (int) getpid(),
                     (int) syscall(__NR_gettid), name[0] ? name : "?");
    n += snprintf(line + n, sizeof(line) - (size_t) n, "%s", msg);
    if (n > (int) sizeof(line) - 2) n = (int) sizeof(line) - 2;
    line[n++] = '\n';

    ssize_t ignored = ::write(g_fd, line, (size_t) n);
    (void) ignored;
}

bool ready() {
    return g_fd >= 0;
}

}  // namespace DiagSink

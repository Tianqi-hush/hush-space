//
// ExitTrace.cpp
//
// 目的只有一个：搞清楚 guest 进程「为什么消失」。
//
// 加固壳检测到"环境不对"时的标准做法是直接退出，进程瞬间消失，Java 层收不到
// 任何异常，logcat 里也没有 tombstone（这台机器的 logcat 还是加密的 HKS 格式，
// 外部根本读不到），就是用户说的"点开闪退"。
//
// 这里铺三层网：
//   1) hook exit/_exit/_Exit/abort/kill/tgkill —— 走 libc 的退出路径
//   2) hook syscall() —— 绕过 libc 直接 syscall(__NR_exit_group) 的退出路径
//   3) 装致命信号处理器 —— SIGSEGV/ABRT/ILL/BUS 这类"直接崩"，记录 pc/lr 属于哪个 so
//
// 所有输出走 DiagSink 里那个在重定向生效前打开的 fd，不会递归、不会丢。
//

#include <jni.h>

#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <inttypes.h>

#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>

#include "Log.h"
#include "DiagSink.h"
#include "JniGuard.h"
#include "JniEnvGuard.h"
#include "Dobby/dobby.h"

namespace {

typedef void (*exit_t)(int);
typedef void (*abort_t)(void);
typedef int (*kill_t)(pid_t, int);
typedef int (*tgkill_t)(pid_t, pid_t, int);
typedef long (*syscall_t)(long, ...);
typedef void (*assert2_t)(const char *, int, const char *, const char *);
typedef void (*abortmsg_t)(const char *);

exit_t g_exit = nullptr;
exit_t g__exit = nullptr;
abort_t g_abort = nullptr;
kill_t g_kill = nullptr;
tgkill_t g_tgkill = nullptr;
syscall_t g_syscall = nullptr;
assert2_t g_assert2 = nullptr;
abortmsg_t g_abortMsg = nullptr;

/** 防止 trampoline 为空时自递归成栈溢出。 */
__thread int g_inExit = 0;

const char *libcSymOf(uintptr_t addr, uintptr_t *delta);

/** 把地址解析成 "libXxx.so+0x1234" 这种形态；libc 的还会带上近似函数名。 */
void symOf(void *addr, char *out, size_t cap) {
    if (addr == nullptr) {
        snprintf(out, cap, "null");
        return;
    }
    Dl_info info{};
    if (dladdr(addr, &info) != 0 && info.dli_fname != nullptr) {
        const char *base = strrchr(info.dli_fname, '/');
        base = (base == nullptr) ? info.dli_fname : base + 1;
        unsigned long off = (unsigned long) addr - (unsigned long) info.dli_fbase;
        snprintf(out, cap, "%s+0x%lx", base, off);
        // libc 里的偏移没人记得住，直接给出最近的导出函数
        if (strncmp(base, "libc.so", 7) == 0) {
            uintptr_t delta = 0;
            const char *fn = libcSymOf((uintptr_t) addr, &delta);
            if (fn != nullptr) {
                size_t used = strlen(out);
                snprintf(out + used, cap - used, "(~%s+0x%lx)", fn, (unsigned long) delta);
            }
        }
    } else {
        snprintf(out, cap, "%p", addr);
    }
}

// ── 可读内存区间快照 ──────────────────────────────────────────────────
//
// 在信号处理器里走栈帧，最大的风险是"fp 是垃圾、读它反而二次崩溃"，
// 一旦二次崩溃就只能走默认动作，连日志都留不下。
// 因此在 install 阶段（还没装任何 hook，不会递归）把 /proc/self/maps 的
// 可读区间存下来，信号里只做区间判断，绝不盲读。

struct Range {
    uintptr_t lo;
    uintptr_t hi;
};

const int kMaxRange = 512;
Range g_ranges[kMaxRange];
int g_rangeCount = 0;

uintptr_t parseHex(const char **pp) {
    const char *p = *pp;
    uintptr_t v = 0;
    for (;;) {
        char c = *p;
        int d;
        if (c >= '0' && c <= '9') d = c - '0';
        else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
        else if (c >= 'A' && c <= 'F') d = c - 'A' + 10;
        else break;
        v = (v << 4) | (uintptr_t) d;
        p++;
    }
    *pp = p;
    return v;
}

void snapshotRanges() {
    int fd = ::open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    static char buf[128 * 1024];
    size_t off = 0;
    for (;;) {
        if (off >= sizeof(buf) - 1) break;
        ssize_t n = ::read(fd, buf + off, sizeof(buf) - 1 - off);
        if (n <= 0) break;
        off += (size_t) n;
    }
    ::close(fd);
    buf[off] = '\0';

    const char *p = buf;
    g_rangeCount = 0;
    while (*p != '\0' && g_rangeCount < kMaxRange) {
        uintptr_t lo = parseHex(&p);
        if (*p != '-') {
            // 解析失败就跳到下一行
            while (*p != '\0' && *p != '\n') p++;
            if (*p == '\n') p++;
            continue;
        }
        p++;  // '-'
        uintptr_t hi = parseHex(&p);
        // 跳过一个空格，检查权限位第一位是不是 'r'
        if (*p == ' ') p++;
        bool rd = (*p == 'r');
        if (rd && hi > lo) {
            g_ranges[g_rangeCount].lo = lo;
            g_ranges[g_rangeCount].hi = hi;
            g_rangeCount++;
        }
        while (*p != '\0' && *p != '\n') p++;
        if (*p == '\n') p++;
    }
}

// 定义在下面（信号处理里用），这里提前声明给可读性判断用
extern volatile sig_atomic_t g_inSignal;

/**
 * [a, a+n) 是否可读。
 *
 * 先查 install 阶段抓的 maps 快照（无系统调用，信号处理器里也能用）。
 * 但快照会过期 —— ART 的堆、JIT 代码、dlopen 进来的 so 全都是 install 之后
 * 才 mmap 的。只信快照的后果上一版已经踩到了：把 ART 堆里的合法字符串指针
 * （0xb400...）当成坏指针，白白替换成空串。
 * 所以快照没命中时再用 mincore 现问一次内核：映射在，就认为可读。
 */
bool readable(uintptr_t a, size_t n) {
    if (a < 0x1000) return false;
    if (a + n < a) return false;
    for (int i = 0; i < g_rangeCount; i++) {
        if (a >= g_ranges[i].lo && a + n <= g_ranges[i].hi)
            return true;
    }
    if (g_inSignal) return false;   // 处理崩溃时不做系统调用

    static long pageSize = 0;
    if (pageSize == 0) pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) pageSize = 4096;

    uintptr_t page = a & ~((uintptr_t) pageSize - 1);
    unsigned char vec = 0;
    if (syscall(__NR_mincore, page, (size_t) pageSize, &vec) == 0) return true;
    return false;
}

// ── libc 符号邻近度表 ─────────────────────────────────────────────────
//
// libart / libunisec 的内部符号拿不到（没有对应版本的 so），但 libc 的函数
// 全都能通过 dladdr 拿到真实地址。装表之后，崩溃地址可以直接说成
// "≈ strlen+0x1c"，不用再靠 0x719b0 这种偏移猜。

struct SymOff {
    const char *name;
    uintptr_t off;
};

const int kMaxLibcSym = 80;
SymOff g_libcSyms[kMaxLibcSym];
int g_libcSymCount = 0;
uintptr_t g_libcBase = 0;

/**
 * 全部走 dlsym 按名字取：既躲开一堆系统头文件的可移植性问题，
 * 也能保证拿到的就是 libc 里的真实实现地址（而不是本库的 PLT 桩）。
 */
void addLibcSym(const char *name) {
    if (g_libcSymCount >= kMaxLibcSym || g_libcBase == 0) return;
    void *fn = dlsym(RTLD_DEFAULT, name);
    if (fn == nullptr) return;
    if ((uintptr_t) fn < g_libcBase) return;
    g_libcSyms[g_libcSymCount].name = name;
    g_libcSyms[g_libcSymCount].off = (uintptr_t) fn - g_libcBase;
    g_libcSymCount++;
}

void buildLibcSymTable() {
    Dl_info di{};
    if (dladdr((void *) strlen, &di) == 0 || di.dli_fbase == nullptr) return;
    g_libcBase = (uintptr_t) di.dli_fbase;

    // 只会被 ART / 加固壳在内存或字符串上踩到的那批
    static const char *names[] = {
            "strlen", "strnlen", "strcmp", "strncmp", "strcasecmp", "strcpy", "strncpy",
            "strcat", "strncat", "strstr", "strchr", "strrchr", "strdup", "strtok",
            "memcpy", "memmove", "memset", "memcmp", "memchr",
            "snprintf", "vsnprintf", "sprintf", "sscanf", "atoi", "strtol",
            "malloc", "calloc", "realloc", "free", "abort",
            "open", "openat", "read", "write", "close", "lseek",
            "mmap", "munmap", "mprotect", "dlopen", "dlsym",
            "pthread_mutex_lock", "pthread_mutex_unlock", "pthread_create",
            "syscall", "gettimeofday", "clock_gettime",
            "fwrite", "fread", "fprintf", "fopen", "fclose",
            "__stack_chk_fail", "__assert2", "__errno", "__cxa_atexit",
    };
    for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); i++) addLibcSym(names[i]);
}

/** 崩溃地址落在哪个 libc 函数区间里（±4KB 内视为命中）。 */
const char *libcSymOf(uintptr_t addr, uintptr_t *delta) {
    if (g_libcBase == 0 || addr < g_libcBase || g_libcSymCount == 0) return nullptr;
    uintptr_t off = addr - g_libcBase;
    const SymOff *best = nullptr;
    for (int i = 0; i < g_libcSymCount; i++) {
        if (g_libcSyms[i].off <= off && (best == nullptr || g_libcSyms[i].off > best->off))
            best = &g_libcSyms[i];
    }
    if (best == nullptr || off - best->off > 0x1000) return nullptr;
    if (delta != nullptr) *delta = off - best->off;
    return best->name;
}

/** 把一段代码地址前面的指令原样打出来：看调用点用的是哪个寄存器/哪个表偏移。 */
void dumpInsBefore(uintptr_t retAddr, int words) {
    if (!readable(retAddr - (uintptr_t) (words * 4), (size_t) (words * 4))) return;
    char line[256];
    int n = snprintf(line, sizeof(line), "INS @%p -", (void *) retAddr);
    for (int i = words; i >= 1; i--) {
        uint32_t w = *(const uint32_t *) (retAddr - (uintptr_t) (i * 4));
        if (n < (int) sizeof(line) - 12)
            n += snprintf(line + n, sizeof(line) - (size_t) n, " %08x", w);
    }
    DiagSink::write("%s", line);
}

/** 走 x29 链（AOSP 的 arm64 库绝大多数保留了帧指针）。 */
void dumpFpChain(uintptr_t fp, uintptr_t sp) {
    char s[192];
    uintptr_t cur = fp;
    for (int i = 0; i < 24; i++) {
        if (!readable(cur, 16)) break;
        uintptr_t next = ((const uintptr_t *) cur)[0];
        uintptr_t lr = ((const uintptr_t *) cur)[1];
        if (lr < 0x1000) break;
        symOf((void *) lr, s, sizeof(s));
        const char *jn = JniGuard::nameOf((void *) lr);
        if (jn != nullptr)
            DiagSink::write("BT #%02d %s [%s]", i + 1, s, jn);
        else
            DiagSink::write("BT #%02d %s", i + 1, s);
        if (i < 4) dumpInsBefore(lr, 3);
        if (next <= cur || next < sp) break;
        cur = next;
    }
}

struct UnwindBuf {
    int n;
    const char *tag;
};

_Unwind_Reason_Code unwindCb(struct _Unwind_Context *ctx, void *arg) {
    UnwindBuf *b = (UnwindBuf *) arg;
    uintptr_t ip = (uintptr_t) _Unwind_GetIP(ctx);
    if (ip != 0) {
        char s[192];
        symOf((void *) ip, s, sizeof(s));
        const char *jn = JniGuard::nameOf((void *) ip);
        if (jn != nullptr)
            DiagSink::write("%s #%02d %s [%s]", b->tag, b->n, s, jn);
        else
            DiagSink::write("%s #%02d %s", b->tag, b->n, s);
        // 只给最靠上的几帧打指令：调用点长什么样，一眼就能看出用的是哪个表
        if (b->n >= 3 && b->n <= 8) dumpInsBefore(ip, 4);
        b->n++;
    }
    return b->n >= 24 ? _URC_NORMAL_STOP : _URC_NO_REASON;
}

// ── 退出路径 hook ─────────────────────────────────────────────────────

void my_exit(int status) {
    if (g_inExit) {
        if (g__exit) g__exit(status);
        syscall(__NR_exit_group, status);
        return;
    }
    g_inExit = 1;
    char s[256];
    symOf(__builtin_return_address(0), s, sizeof(s));
    DiagSink::write("EXIT exit(%d) caller=%s", status, s);
    if (g_exit) g_exit(status);
    if (g__exit) g__exit(status);
    syscall(__NR_exit_group, status);
}

void my__exit(int status) {
    if (g_inExit) {
        syscall(__NR_exit_group, status);
        return;
    }
    g_inExit = 1;
    char s[256];
    symOf(__builtin_return_address(0), s, sizeof(s));
    DiagSink::write("EXIT _exit(%d) caller=%s", status, s);
    if (g__exit) g__exit(status);
    syscall(__NR_exit_group, status);
}

void my_abort() {
    if (g_inExit) {
        if (g_abort) g_abort();
        syscall(__NR_exit_group, 134);
        return;
    }
    g_inExit = 1;
    char s[256];
    symOf(__builtin_return_address(0), s, sizeof(s));
    DiagSink::write("EXIT abort() caller=%s", s);
    g_inExit = 0;
    if (g_abort) g_abort();  // 保留 SIGABRT -> tombstone 的原行为
    syscall(__NR_exit_group, 134);
}

int my_kill(pid_t pid, int sig) {
    if (pid == getpid() && !g_inExit) {
        char s[256];
        symOf(__builtin_return_address(0), s, sizeof(s));
        DiagSink::write("EXIT kill(self sig=%d) caller=%s", sig, s);
    }
    return g_kill ? g_kill(pid, sig) : -1;
}

int my_tgkill(pid_t tgid, pid_t tid, int sig) {
    if (tgid == getpid() && !g_inExit) {
        char s[256];
        symOf(__builtin_return_address(0), s, sizeof(s));
        DiagSink::write("EXIT tgkill(self tid=%d sig=%d) caller=%s", (int) tid, sig, s);
    }
    return g_tgkill ? g_tgkill(tgid, tid, sig) : -1;
}

/** 绕开 libc 的退出：syscall(__NR_exit_group, n) / syscall(__NR_exit, n)。 */
long my_syscall(long number, ...) {
    long a1 = 0, a2 = 0, a3 = 0, a4 = 0, a5 = 0, a6 = 0;
    va_list ap;
    va_start(ap, number);
    a1 = va_arg(ap, long);
    a2 = va_arg(ap, long);
    a3 = va_arg(ap, long);
    a4 = va_arg(ap, long);
    a5 = va_arg(ap, long);
    a6 = va_arg(ap, long);
    va_end(ap);

    if (!g_inExit &&
        (number == __NR_exit_group || number == __NR_exit ||
         number == __NR_kill || number == __NR_tgkill || number == __NR_tkill)) {
        char s[256];
        symOf(__builtin_return_address(0), s, sizeof(s));
        DiagSink::write("EXIT syscall(%ld, a1=%ld a2=%ld) caller=%s", number, a1, a2, s);
        if (number == __NR_exit_group || number == __NR_exit) g_inExit = 1;
    }

    if (g_syscall) return g_syscall(number, a1, a2, a3, a4, a5, a6);
    // 理论上到不了这里（g_syscall 为空说明 hook 没挂上，就不会被调用）。
    // 绝对不能回落到 ::syscall —— 那是我们自己的入口，会无限递归。
    return -1;
}

void my_assert2(const char *file, int line, const char *function, const char *msg) {
    char s[256];
    symOf(__builtin_return_address(0), s, sizeof(s));
    DiagSink::write("ASSERT %s:%d %s(): %s caller=%s", file ? file : "?", line,
                    function ? function : "?", msg ? msg : "?", s);
    if (g_assert2) g_assert2(file, line, function, msg);
}

void my_abortMsg(const char *msg) {
    DiagSink::write("ABORT-MESSAGE %s", msg ? msg : "(null)");
    if (g_abortMsg) g_abortMsg(msg);
}

// ── 致命信号 ──────────────────────────────────────────────────────────

volatile sig_atomic_t g_inSignal = 0;

const int kSignals[] = {SIGSEGV, SIGABRT, SIGILL, SIGBUS, SIGFPE, SIGTRAP};
struct sigaction g_old[6];
bool g_oldValid[6];

/** 页内空指针事件的记录额度：够看清有没有在反复发生，又不至于刷爆日志。 */
std::atomic<int> g_nullLogs(24);

/**
 * 把信号原样交还给原来的处理器（ART 的 FaultManager / debuggerd / sigchain）。
 *
 * 这一步至关重要：ART 大量使用「隐式空指针检查」—— 编译 Java 方法时故意不插
 * null 判断，让访问 [0, page) 触发 SIGSEGV，再由它自己的 FaultManager 把这条
 * 指令"跳过"并抛出 NullPointerException。我们后装的处理器一旦把这条链截断，
 * 一个本来能恢复的 Java 异常就变成了进程猝死。
 */
void chainToOld(int sig, siginfo_t *si, void *ucv) {
    for (int i = 0; i < (int) (sizeof(kSignals) / sizeof(kSignals[0])); i++) {
        if (kSignals[i] != sig) continue;
        if (g_oldValid[i]) {
            if (g_old[i].sa_flags & SA_SIGINFO) {
                if (g_old[i].sa_sigaction != nullptr) {
                    g_old[i].sa_sigaction(sig, si, ucv);
                    return;
                }
            } else if (g_old[i].sa_handler != SIG_DFL && g_old[i].sa_handler != SIG_IGN) {
                // 老式 handler 的语义是"装回默认再调用"，否则下次不会触发
                sigaction(sig, &g_old[i], nullptr);
                g_old[i].sa_handler(sig);
                return;
            }
        }
        break;
    }
    // 没有可链的处理器：恢复默认并重新抛，让系统按正常崩溃流程处理
    signal(sig, SIG_DFL);
    syscall(__NR_tgkill, getpid(), (pid_t) syscall(__NR_gettid), sig);
}

void onFatal(int sig, siginfo_t *si, void *ucv) {
    if (g_inSignal) {
        // 已经在处理里又炸了一次：直接走默认行为，避免死循环
        signal(sig, SIG_DFL);
        syscall(__NR_tgkill, getpid(), (pid_t) syscall(__NR_gettid), sig);
        return;
    }

    const uintptr_t faultAddr = (si != nullptr) ? (uintptr_t) si->si_addr : 0;

    /*
     * 页内地址：这几乎必定是 null 解引用，而不是"坏指针"。
     *
     * 两重理由必须原样放行：
     *   1) ART 用它做隐式空指针检查，能在自己的处理器里恢复执行并抛 NPE；
     *   2) 信号上下文里做回溯/snprintf 本身就危险，对高频的 NPE 更是灾难。
     */
    if ((sig == SIGSEGV || sig == SIGBUS) && faultAddr < 4096) {
        if (DiagSink::ready() && g_nullLogs.fetch_sub(1) > 0) {
            DiagSink::write("SEGV-null addr=%p -> handed to ART", (void *) faultAddr);
        }
        chainToOld(sig, si, ucv);
        return;
    }

    g_inSignal = 1;

    uintptr_t pc = 0, lr = 0, sp = 0, fp = 0;
    uintptr_t r0 = 0, r1 = 0, r2 = 0, r8 = 0;
#if defined(__aarch64__)
    ucontext_t *uc = (ucontext_t *) ucv;
    pc = (uintptr_t) uc->uc_mcontext.pc;
    lr = (uintptr_t) uc->uc_mcontext.regs[30];
    sp = (uintptr_t) uc->uc_mcontext.sp;
    fp = (uintptr_t) uc->uc_mcontext.regs[29];
    r0 = (uintptr_t) uc->uc_mcontext.regs[0];
    r1 = (uintptr_t) uc->uc_mcontext.regs[1];
    r2 = (uintptr_t) uc->uc_mcontext.regs[2];
    r8 = (uintptr_t) uc->uc_mcontext.regs[8];
#elif defined(__arm__)
    ucontext_t *uc = (ucontext_t *) ucv;
    pc = (uintptr_t) uc->uc_mcontext.arm_pc;
    lr = (uintptr_t) uc->uc_mcontext.arm_lr;
    sp = (uintptr_t) uc->uc_mcontext.arm_sp;
    fp = (uintptr_t) uc->uc_mcontext.arm_fp;
    r0 = (uintptr_t) uc->uc_mcontext.arm_r0;
    r1 = (uintptr_t) uc->uc_mcontext.arm_r1;
    r2 = (uintptr_t) uc->uc_mcontext.arm_r2;
#endif

    char s1[192], s2[192];
    symOf((void *) pc, s1, sizeof(s1));
    symOf((void *) lr, s2, sizeof(s2));
    DiagSink::write("SIGNAL %d addr=%p code=%d pc=%s lr=%s sp=%p fp=%p", sig,
                    si ? si->si_addr : nullptr, si ? si->si_code : -1, s1, s2, (void *) sp,
                    (void *) fp);
    // 出事的这几个寄存器往往直接指向罪魁：r0/r1 是传给 libc 的参数，
    // r8 在 arm64 上是 syscall 号（syscall 指令用的寄存器）。
    DiagSink::write("SIGNAL-REG x0=%p x1=%p x2=%p x8=%" PRIxPTR, (void *) r0, (void *) r1,
                    (void *) r2, r8);

    // JNI 层状态 + 崩溃前最后 8 次 GetMethodID 的参数。这一段是判断
    // 「0xfffff9 是被谁塞进 x2 的」的直接证据，必须在回溯之前落盘。
    JniEnvGuard::dumpState();

    // 先把栈帧拉住：即使后面 _Unwind_Backtrace 卡住/二次崩，这两行也已经落盘
    dumpFpChain(fp, sp);

    UnwindBuf ub{0, "UW"};
    _Unwind_Backtrace(unwindCb, &ub);

    // 交还给原来的处理器（ART / debuggerd），保持原有 tombstone 行为
    chainToOld(sig, si, ucv);
}

void installSignalHandlers() {
    // 独立信号栈：栈溢出导致的 SIGSEGV 才能被接住（否则处理器本身也没栈可用）。
    // 但处理器里还要走栈帧回溯，原来的 64KB 太紧，加大到 256KB。
    static char altStack[256 * 1024];
    stack_t ss{};
    ss.ss_sp = altStack;
    ss.ss_size = sizeof(altStack);
    ss.ss_flags = 0;
    sigaltstack(&ss, nullptr);

    struct sigaction sa{};
    sa.sa_sigaction = onFatal;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);

    for (int i = 0; i < (int) (sizeof(kSignals) / sizeof(kSignals[0])); i++) {
        g_oldValid[i] = (sigaction(kSignals[i], &sa, &g_old[i]) == 0);
    }
}

// ── 安装 ──────────────────────────────────────────────────────────────

void *g_hooked[8];
int g_hookedCount = 0;

bool alreadyHooked(void *addr) {
    for (int i = 0; i < g_hookedCount; i++)
        if (g_hooked[i] == addr) return true;
    return false;
}

void hookOne(void *handle, const char *name, void *replace, void **originSlot) {
    void *addr = dlsym(handle, name);
    if (addr == nullptr || alreadyHooked(addr)) return;
    void *origin = nullptr;
    if (DobbyHook(addr, replace, &origin) != 0 || origin == nullptr) {
        ALOGE("exit hook %s failed", name);
        return;
    }
    if (g_hookedCount < (int) (sizeof(g_hooked) / sizeof(g_hooked[0])))
        g_hooked[g_hookedCount++] = addr;
    if (originSlot != nullptr) *originSlot = origin;
}

}  // namespace

namespace ExitTrace {

/** 必须在 NativeIO::install() 之前调用：用真实路径打开日志 fd。 */
void install(const char *diagPath) {
    DiagSink::open(diagPath);
    // 必须在装 hook 之前做：此时 /proc/self/maps 的读取不会被自己的重定向干扰
    snapshotRanges();
    buildLibcSymTable();

    void *handle = dlopen("libc.so", RTLD_NOW);
    if (handle == nullptr) return;

    hookOne(handle, "exit", (void *) my_exit, (void **) &g_exit);
    hookOne(handle, "_exit", (void *) my__exit, (void **) &g__exit);
    hookOne(handle, "_Exit", (void *) my__exit, (void **) &g__exit);
    hookOne(handle, "abort", (void *) my_abort, (void **) &g_abort);
    hookOne(handle, "kill", (void *) my_kill, (void **) &g_kill);
    hookOne(handle, "tgkill", (void *) my_tgkill, (void **) &g_tgkill);
    hookOne(handle, "syscall", (void *) my_syscall, (void **) &g_syscall);
    hookOne(handle, "__assert2", (void *) my_assert2, (void **) &g_assert2);
    hookOne(handle, "android_set_abort_message", (void *) my_abortMsg, (void **) &g_abortMsg);

    installSignalHandlers();

    DiagSink::write("EXIT-TRACE armed hooks=%d diagFd=%d", g_hookedCount, DiagSink::fd());
}

/** 给别的模块用：判断一个指针能不能安全读。 */
bool safeToRead(const void *p, size_t n) {
    return readable((uintptr_t) p, n);
}

/** 给别的模块用：把地址写成 "libX.so+0x1234" 的形式。 */
void describe(const void *addr, char *out, size_t cap) {
    symOf((void *) addr, out, cap);
}

/**
 * 在「非崩溃」上下文里主动打一次调用栈。
 *
 * 崩溃处理器里那份栈受信号上下文限制（不能做 I/O 之外的事，unwind 也可能被
 * 打断），而这里是在正常函数里调用，拿到的链更完整、更可信。
 */
void dumpStack(const char *tag) {
    UnwindBuf ub{0, tag != nullptr ? tag : "BT"};
    _Unwind_Backtrace(unwindCb, &ub);
}

}  // namespace ExitTrace

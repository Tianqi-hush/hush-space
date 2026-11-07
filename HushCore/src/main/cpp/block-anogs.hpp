#pragma once

#include <string>
#include <fstream>
#include <thread>
#include <vector>
#include <cstring>
#include <cerrno>
#include <unistd.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/ptrace.h>
#include <sys/ucontext.h>
#include <signal.h>
#include <link.h>
#include <elf.h>
#include <linux/seccomp.h>
#include <linux/filter.h>
#include <sys/time.h>
#include <cstdint>
#include <cstdio>
#include <android/log.h>

#define BLOCK_ANOGS_LOG_TAG "block-anogs"
#define BA_LOGI(...) __android_log_print(ANDROID_LOG_INFO, BLOCK_ANOGS_LOG_TAG, __VA_ARGS__)

#if defined(__aarch64__)
    constexpr int NR_OPENAT = 56;
    constexpr int NR_OPENAT2 = 437;
    constexpr int NR_EXECVE = 221;
    constexpr int NR_EXECVEAT = 281;
    constexpr int NR_PTRACE = 117;
    constexpr int NR_PROCESS_VM_WRITEV = 271;
    constexpr int NR_PROCESS_VM_READV = 270;
    constexpr int NR_MEMFD_CREATE = 279;
    constexpr int NR_SECCOMP = 277;
    constexpr int NR_GETDENTS64 = 61;
#elif defined(__arm__)
    constexpr int NR_OPENAT = 322;
    constexpr int NR_OPEN = 5;
    constexpr int NR_OPENAT2 = 437;
    constexpr int NR_EXECVE = 11;
    constexpr int NR_EXECVEAT = 387;
    constexpr int NR_PTRACE = 26;
    constexpr int NR_PROCESS_VM_WRITEV = 377;
    constexpr int NR_PROCESS_VM_READV = 376;
    constexpr int NR_MEMFD_CREATE = 385;
    constexpr int NR_SECCOMP = 383;
    constexpr int NR_GETDENTS64 = 141;
#else
    #error "Unsupported architecture"
#endif

#ifndef RLIMIT_NPROC
    #define RLIMIT_NPROC 6
#endif

constexpr size_t ELF_HEADER_SIZE = 64;
constexpr size_t NUKE_PAGE_SIZE = 4096;

/** Manual layer mask (README order). Layer 7 is enumerate-only (unlink removed — was SIGSEGV). */
constexpr uint32_t NUKE_L1_FILE_LOCK = 1u << 0;
constexpr uint32_t NUKE_L2_FD_EXHAUST = 1u << 1;
constexpr uint32_t NUKE_L3_MLOCK = 1u << 2;
constexpr uint32_t NUKE_L4_ELF_DESTROY = 1u << 3;
constexpr uint32_t NUKE_L5_SHDR_HIDE = 1u << 4;
constexpr uint32_t NUKE_L6_GUARD_PAGE = 1u << 5;
constexpr uint32_t NUKE_L7_LINKMAP = 1u << 6;
constexpr uint32_t NUKE_L8_ANTIDEBUG = 1u << 7;
constexpr uint32_t NUKE_L9_SIGNALS = 1u << 8;
constexpr uint32_t NUKE_L10_SECCOMP = 1u << 9;
constexpr uint32_t NUKE_L11_WATCHDOG = 1u << 10;
/** Extra: slow read open before chmod (not in README’s 11). */
constexpr uint32_t NUKE_HOLD_FILE_READ = 1u << 11;

constexpr uint32_t NUKE_LAYERS_README_11 =
        NUKE_L1_FILE_LOCK | NUKE_L2_FD_EXHAUST | NUKE_L3_MLOCK | NUKE_L4_ELF_DESTROY | NUKE_L5_SHDR_HIDE |
        NUKE_L6_GUARD_PAGE | NUKE_L7_LINKMAP | NUKE_L8_ANTIDEBUG | NUKE_L9_SIGNALS | NUKE_L10_SECCOMP |
        NUKE_L11_WATCHDOG;
constexpr uint32_t NUKE_LAYERS_ALL = NUKE_LAYERS_README_11 | NUKE_HOLD_FILE_READ;

namespace nuke {
    inline volatile sig_atomic_t blocked = 0;
    inline void* lib_base = nullptr;
    inline size_t lib_size = 0;
    inline std::vector<int> dummy_fds;
    /** Filled when {@code nukeLibrary} runs with {@code NUKE_L11_WATCHDOG}: persistent upkeep. */
    inline std::string watch_name_substr;
    inline std::string watch_so_path;
    inline uint32_t watch_repeat_layers = 0;
}

inline std::string getLibraryPath(const std::string& name) {
    std::ifstream maps("/proc/self/maps");
    std::string line;

    while (std::getline(maps, line)) {
        if (line.find(name) == std::string::npos) continue;

        size_t path_start = line.find('/');
        if (path_start == std::string::npos) continue;

        size_t path_end = line.find(' ', path_start);
        if (path_end != std::string::npos) {
            return line.substr(path_start, path_end - path_start);
        }
        return line.substr(path_start);
    }
    return "";
}

inline void getLibraryInfo(const std::string& name, void** base, size_t* size) {
    std::ifstream maps("/proc/self/maps");
    std::string line;
    *base = nullptr;
    *size = 0;

    unsigned long min_start = 0;
    unsigned long max_end = 0;
    bool found = false;

    while (std::getline(maps, line)) {
        if (line.find(name) == std::string::npos) continue;

        unsigned long seg_start = strtoul(line.c_str(), nullptr, 16);
        size_t dash = line.find('-');
        if (dash == std::string::npos) continue;

        unsigned long seg_end = strtoul(line.substr(dash + 1).c_str(), nullptr, 16);
        if (!found) {
            min_start = seg_start;
            max_end = seg_end;
            found = true;
            continue;
        }
        if (seg_start < min_start) min_start = seg_start;
        if (seg_end > max_end) max_end = seg_end;
    }

    if (found) {
        *base = reinterpret_cast<void*>(min_start);
        *size = static_cast<size_t>(max_end - min_start);
    }
}

inline void sigTrapHandler(int sig, siginfo_t* info, void* context) {
    (void)sig;
    (void)info;

    if (!nuke::blocked) return;

    ucontext_t* uc = static_cast<ucontext_t*>(context);
#if defined(__arm__)
    uc->uc_mcontext.arm_pc += 4;
#elif defined(__aarch64__)
    uc->uc_mcontext.pc += 4;
#endif
}

inline void setupSignalHandlers() {
    struct sigaction sa;
    sa.sa_sigaction = sigTrapHandler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGTRAP, &sa, nullptr);
    sigaction(SIGILL, &sa, nullptr);
}

inline void slowFileReadFd(int fd) {
    if (fd < 0) {
        return;
    }
    char buffer[4096];
    for (;;) {
        ssize_t n = read(fd, buffer, sizeof(buffer));
        if (n <= 0) {
            break;
        }
        usleep(100000);
    }
    close(fd);
}

inline void slowFileRead(const std::string& path) {
    slowFileReadFd(open(path.c_str(), O_RDONLY));
}

inline void preventPtrace() {
    (void)ptrace(PTRACE_TRACEME, 0, nullptr, nullptr);
}

extern "C" {
static int nukeHideFromLinkMapPhdrCb(struct dl_phdr_info* info, size_t size, void* data) {
    (void)size;
    const char* target = static_cast<const char*>(data);
    if (target && info->dlpi_name != nullptr && info->dlpi_name[0] != '\0' &&
        strstr(info->dlpi_name, target) != nullptr) {
        // Unlinking link_map via dlpi_addr was unsafe (SIGSEGV). Enumeration-only.
    }
    return 0;
}
}

inline void hideFromLinkMap(const std::string& name) {
    dl_iterate_phdr(nukeHideFromLinkMapPhdrCb, const_cast<char*>(name.c_str()));
}

inline void exhaustFileDescriptors() {
    struct rlimit rl;
    getrlimit(RLIMIT_NOFILE, &rl);

    int dev_null = open("/dev/null", O_RDONLY);
    if (dev_null < 0) return;

    while (nuke::dummy_fds.size() < rl.rlim_cur - 50) {
        int fd = dup(dev_null);
        if (fd < 0) break;
        nuke::dummy_fds.push_back(fd);
    }
    close(dev_null);
}

inline bool mapsRangeFree(uintptr_t start, size_t len) {
    std::ifstream maps("/proc/self/maps");
    std::string line;
    const uintptr_t a0 = start;
    const uintptr_t a1 = start + len;
    while (std::getline(maps, line)) {
        unsigned long s = 0;
        unsigned long e = 0;
        if (sscanf(line.c_str(), "%lx-%lx", &s, &e) != 2) {
            continue;
        }
        if (e > a0 && static_cast<uintptr_t>(s) < a1) {
            return false;
        }
    }
    return true;
}

inline void installGuardPages() {
    if (!nuke::lib_base || !nuke::lib_size) return;

    unsigned char* end = static_cast<unsigned char*>(nuke::lib_base) + nuke::lib_size;
    end = reinterpret_cast<unsigned char*>((reinterpret_cast<uintptr_t>(end) + NUKE_PAGE_SIZE - 1) &
                                           ~(static_cast<uintptr_t>(NUKE_PAGE_SIZE) - 1));

    const uintptr_t gap = reinterpret_cast<uintptr_t>(end);
    if (mapsRangeFree(gap, NUKE_PAGE_SIZE)) {
        void* p = mmap(end, NUKE_PAGE_SIZE, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
        (void)p;
        return;
    }
    (void)mmap(nullptr, NUKE_PAGE_SIZE, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
}

inline void installSeccompFilter() {
#if defined(__arm__)
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD + BPF_W + BPF_ABS, static_cast<unsigned int>(offsetof(struct seccomp_data, nr))),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_OPENAT, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_OPEN, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_OPENAT2, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_GETDENTS64, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_EXECVE, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_EXECVEAT, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_PTRACE, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_PROCESS_VM_WRITEV, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_PROCESS_VM_READV, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_MEMFD_CREATE, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ALLOW),
    };
#else
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD + BPF_W + BPF_ABS, static_cast<unsigned int>(offsetof(struct seccomp_data, nr))),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_OPENAT, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_OPENAT2, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_GETDENTS64, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_EXECVE, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_EXECVEAT, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_PTRACE, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_PROCESS_VM_WRITEV, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_PROCESS_VM_READV, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K, NR_MEMFD_CREATE, 0, 1),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ALLOW),
    };
#endif

    const unsigned short filter_len = sizeof(filter) / sizeof(filter[0]);
    struct sock_fprog prog = { filter_len, filter };

    prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
    syscall(NR_SECCOMP, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &prog);
}

inline void destroyElfHeaders() {
    if (!nuke::lib_base) return;

    mprotect(nuke::lib_base, NUKE_PAGE_SIZE, PROT_READ | PROT_WRITE);
    memset(nuke::lib_base, 0, ELF_HEADER_SIZE);

    unsigned char fake_elf[] = {0x7f, 'E', 'L', 'F', 0, 0, 0, 0};
    memcpy(nuke::lib_base, fake_elf, 8);

    mprotect(nuke::lib_base, NUKE_PAGE_SIZE, PROT_READ);
}

inline void hideSectionHeaders() {
    if (!nuke::lib_base) return;

    Elf64_Ehdr* ehdr = static_cast<Elf64_Ehdr*>(nuke::lib_base);
    if (!ehdr->e_shoff) return;

    mprotect(nuke::lib_base, NUKE_PAGE_SIZE, PROT_READ | PROT_WRITE);
    ehdr->e_shoff = 0;
    ehdr->e_shnum = 0;
    ehdr->e_shstrndx = 0;
    mprotect(nuke::lib_base, NUKE_PAGE_SIZE, PROT_READ);
}

inline void lockFilePermissions(const std::string& path) {
    struct stat st;
    if (stat(path.c_str(), &st) != 0) return;

    struct timespec orig_times[2];
    orig_times[0] = st.st_atim;
    orig_times[1] = st.st_mtim;

    chmod(path.c_str(), 0000);
    utimensat(AT_FDCWD, path.c_str(), orig_times, 0);
}

/**
 * Layer 11: infinite loop (~1s). While target .so stays mapped, re-applies layers that are safe to
 * repeat (file can be chmod’d again; headers can be re-damaged if something restored them).
 * Seccomp, FD exhaustion, and guard page stay process-wide — not re-run here.
 */
inline void installWatchdog() {
    std::thread([]() {
        const std::string name = nuke::watch_name_substr;
        const std::string path = nuke::watch_so_path;
        const uint32_t L = nuke::watch_repeat_layers;
        if (name.empty()) {
            BA_LOGI("watchdog: skipped (empty name)");
            return;
        }
        BA_LOGI("watchdog (L11): thread started name=%s path=%s repeat_mask=0x%08x", name.c_str(),
                path.c_str(), L);
        for (;;) {
            sleep(1);

            bool seen_exec = false;
            {
                std::ifstream maps("/proc/self/maps");
                std::string line;
                while (std::getline(maps, line)) {
                    if (line.find(name) != std::string::npos && line.find("r-xp") != std::string::npos) {
                        seen_exec = true;
                        break;
                    }
                }
            }
            if (!seen_exec) {
                continue;
            }

            getLibraryInfo(name, &nuke::lib_base, &nuke::lib_size);

            BA_LOGI("watchdog: target still mapped → reapply");

            if ((L & NUKE_L5_SHDR_HIDE) != 0u) {
                hideSectionHeaders();
            }
            if ((L & NUKE_L4_ELF_DESTROY) != 0u) {
                destroyElfHeaders();
            }
            if ((L & NUKE_L1_FILE_LOCK) != 0u && !path.empty()) {
                lockFilePermissions(path);
            }
            if ((L & NUKE_L7_LINKMAP) != 0u) {
                hideFromLinkMap(name);
            }
            if ((L & NUKE_L3_MLOCK) != 0u && nuke::lib_base && nuke::lib_size) {
                (void)mlock(nuke::lib_base, nuke::lib_size);
            }
        }
    }).detach();
}

inline void setupAntiDebugPrctl() {
    prctl(PR_SET_DUMPABLE, 0);
    prctl(PR_SET_PTRACER, PR_SET_PTRACER_ANY, 0, 0, 0);

    struct rlimit rl = {0, 0};
    setrlimit(RLIMIT_CORE, &rl);
    setrlimit(RLIMIT_NPROC, &rl);

    preventPtrace();
}

inline void setupAntiDebug() {
    setupAntiDebugPrctl();
    setupSignalHandlers();
}

inline void nukeLibrary(const std::string& name, uint32_t layers = NUKE_LAYERS_ALL) {
    BA_LOGI("nukeLibrary: entry substr=%s mask=0x%08x", name.c_str(), layers);

    std::string path;
    do {
        path = getLibraryPath(name);
        if (path.empty()) usleep(1000);
    } while (path.empty());

    getLibraryInfo(name, &nuke::lib_base, &nuke::lib_size);

    BA_LOGI("nukeLibrary: resolved path=%s base=%p size=%zu", path.c_str(), nuke::lib_base,
            (size_t)nuke::lib_size);

    const bool anyTrapHandling = (layers & (NUKE_L8_ANTIDEBUG | NUKE_L9_SIGNALS)) != 0u;
    if (anyTrapHandling || (layers & (NUKE_L4_ELF_DESTROY | NUKE_L5_SHDR_HIDE)) != 0u) {
        nuke::blocked = 1;
    }

    if ((layers & NUKE_L3_MLOCK) != 0u && nuke::lib_base && nuke::lib_size) {
        BA_LOGI("layer L3: mlock");
        mlock(nuke::lib_base, nuke::lib_size);
    }

    if ((layers & NUKE_L5_SHDR_HIDE) != 0u) {
        BA_LOGI("layer L5: hideSectionHeaders");
        hideSectionHeaders();
    }
    if ((layers & NUKE_L4_ELF_DESTROY) != 0u) {
        BA_LOGI("layer L4: destroyElfHeaders");
        destroyElfHeaders();
    }

    if ((layers & NUKE_L8_ANTIDEBUG) != 0u) {
        BA_LOGI("layer L8: anti-debug (prctl/rlimit/ptrace)");
        setupAntiDebugPrctl();
    }
    if ((layers & NUKE_L9_SIGNALS) != 0u) {
        BA_LOGI("layer L9: signal handlers");
        setupSignalHandlers();
    }

    if ((layers & NUKE_L7_LINKMAP) != 0u) {
        BA_LOGI("layer L7: dl_iterate_phdr (enumerate only)");
        hideFromLinkMap(name);
    }

    if ((layers & NUKE_L2_FD_EXHAUST) != 0u) {
        BA_LOGI("layer L2: exhaustFileDescriptors");
        exhaustFileDescriptors();
    }
    if ((layers & NUKE_L6_GUARD_PAGE) != 0u) {
        BA_LOGI("layer L6: installGuardPages");
        installGuardPages();
    }

    if ((layers & NUKE_L10_SECCOMP) != 0u) {
        BA_LOGI("layer L10: installSeccompFilter");
        installSeccompFilter();
    }

    int hold_fd = -1;
    if ((layers & NUKE_HOLD_FILE_READ) != 0u) {
        BA_LOGI("hold: slowFileReadFd thread");
        hold_fd = open(path.c_str(), O_RDONLY);
        if (hold_fd >= 0) {
            std::thread([hold_fd]() { slowFileReadFd(hold_fd); }).detach();
        }
    }
    if ((layers & NUKE_L1_FILE_LOCK) != 0u) {
        BA_LOGI("layer L1: lockFilePermissions");
        lockFilePermissions(path);
    }

    if ((layers & NUKE_L11_WATCHDOG) != 0u) {
        BA_LOGI("layer L11: starting watchdog thread");
        nuke::watch_name_substr = name;
        nuke::watch_so_path = path;
        nuke::watch_repeat_layers = layers;
        installWatchdog();
    }

    BA_LOGI("nukeLibrary: initial pass finished (log tag=%s)", BLOCK_ANOGS_LOG_TAG);
}

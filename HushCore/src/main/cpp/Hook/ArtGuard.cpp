//
// ArtGuard.cpp
//
// 上一轮（1.3.0）的日志把链路彻底钉死了：
//
//   STR-BAD strlen(0xfffff9) caller=libart.so+0x233984
//   STR-BT #02 libart.so+0x233984     ← strlen 的返回地址
//   STR-BT #03 libart.so+0x2b71c0     ← 调用者的返回地址
//   STR-BT #04 libart.so+0x688158
//   STR-BT #05 libunisec.so+0x7f264
//   STR-BT #06 libunisec.so+0x54900
//
// 拦住 strlen 之后，进程并没有活下来，而是在 8 字节之后又炸了一次
// （SIGNAL 11 addr=0x10 pc=libart.so+0x2b71c8）。说明这个坏指针在同一个函数里
// 还会被第二次使用 —— 只在 strlen 上做手脚治不了根。
//
// 真正的解法是把参数在**函数入口**就换掉。这里用 .eh_frame_hdr 的检索表把
// 「调用位置」反查成「函数起始地址」，然后在入口把那个明显不可能是指针的值
// 换成空串。找不到 / 校验不过就只记录不挂钩：宁可少一层网，不能挂错函数。
//

#include "ArtGuard.h"

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>

#include <dlfcn.h>
#include <link.h>

#include "Log.h"
#include "DiagSink.h"
#include "ExitTrace.h"
#include "Dobby/dobby.h"

namespace {

/**
 * 实测偏移（同一 ROM 上四次运行完全一致）：
 *   libart.so+0x2b71c0 = 调用「字符串 -> ART 对象」那个函数的返回地址。
 *
 * 这个值只是"线索"，真正的入口由 .eh_frame_hdr 反查得出，并且要过序言校验。
 * 换 ROM 时它会失效 —— 那时校验会失败，我们退化成只记录，不会瞎挂。
 */
const uintptr_t kCallSiteOff = 0x2b71c0;

struct ArtInfo {
    uintptr_t base = 0;
    uintptr_t ehHdr = 0;
    uintptr_t textLo = 0;
    uintptr_t textHi = 0;
    bool found = false;
};

ArtInfo g_art;

int phdrCb(dl_phdr_info *info, size_t, void *) {
    if (info == nullptr || info->dlpi_name == nullptr) return 0;
    if (strstr(info->dlpi_name, "libart.so") == nullptr) return 0;

    g_art.base = (uintptr_t) info->dlpi_addr;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) &ph = info->dlpi_phdr[i];
        const uintptr_t vaddr = g_art.base + (uintptr_t) ph.p_vaddr;
        if (ph.p_type == PT_GNU_EH_FRAME) {
            g_art.ehHdr = vaddr;
        } else if (ph.p_type == PT_LOAD && (ph.p_flags & PF_X) != 0) {
            g_art.textLo = vaddr;
            g_art.textHi = vaddr + (uintptr_t) ph.p_memsz;
        }
    }
    g_art.found = (g_art.base != 0 && g_art.ehHdr != 0);
    return 1;
}

/**
 * 从 .eh_frame_hdr 的二分检索表里反查 target 所在函数的起始地址。
 *
 * 表头 4 字节：version / eh_frame_ptr_enc / fde_count_enc / table_enc。
 * bionic 与 clang 生成的组合固定是 1 / 0x1B(pcrel-sdata4) / 0x03(udata4) /
 * 0x3B(datarel-sdata4)，所以表项就是两个 int32，基址是 .eh_frame_hdr 自身。
 */
uintptr_t findFuncStart(uintptr_t target) {
    if (g_art.ehHdr == 0) return 0;
    const uint8_t *h = (const uint8_t *) g_art.ehHdr;
    if (h[0] != 1 || h[1] != 0x1B || h[2] != 0x03 || h[3] != 0x3B) return 0;

    const uint32_t count = *(const uint32_t *) (h + 8);
    if (count == 0 || count > 2000000u) return 0;

    const int32_t *table = (const int32_t *) (h + 12);
    const uintptr_t dataBase = (uintptr_t) h;

    uint32_t lo = 0, hi = count;
    while (lo < hi) {
        const uint32_t mid = lo + (hi - lo) / 2;
        const uintptr_t start = dataBase + (uintptr_t) (intptr_t) table[mid * 2];
        if (start <= target) lo = mid + 1;
        else hi = mid;
    }
    if (lo == 0) return 0;

    const uintptr_t start = dataBase + (uintptr_t) (intptr_t) table[(lo - 1) * 2];
    if (start > target) return 0;
    // 一个函数体几十 KB 已经顶天；再远就说明表没对上
    if (target - start > 0x40000) return 0;
    return start;
}

/** 函数入口的形态校验：没有标准序言的一律不挂。 */
bool looksLikePrologue(uintptr_t fn) {
    if (fn < g_art.textLo || fn + 4 > g_art.textHi) return false;
    const uint32_t w = *(const uint32_t *) fn;
    if ((w & 0xFFC07FFF) == 0xA9807BFD) return true;  // stp x29, x30, [sp, #imm]!
    if ((w & 0xFFC07FFF) == 0xA9007BFD) return true;  // stp x29, x30, [sp, #imm]
    if ((w & 0xFFC003FF) == 0xD10003FF) return true;  // sub sp, sp, #imm
    return false;
}

typedef void *(*entry_t)(void *, const char *);

entry_t g_orig = nullptr;
std::atomic<bool> g_on(false);
std::atomic<int> g_hits(16);

/**
 * 只认「像指针但绝不可能存在」的那一段：4KB ~ 16MB。
 *
 * 真指针都在 16MB 以上（代码、so、ART 堆、栈），小整数（句柄、长度、错误码）
 * 都在 4KB 以下 —— 两头都不碰，误伤概率极低。这次的 0xfffff9 正好落在中间。
 */
inline bool badStrArg(const void *p) {
    const uintptr_t v = (uintptr_t) p;
    if (v < 0x1000 || v >= (uintptr_t) 0x01000000) return false;
    return !ExitTrace::safeToRead(p, 1);
}

void *my_entry(void *a0, const char *a1) {
    if (!g_on.load(std::memory_order_relaxed)) return g_orig(a0, a1);
    if (badStrArg(a1)) {
        if (g_hits.fetch_sub(1) > 0) {
            char who[192];
            ExitTrace::describe(__builtin_return_address(0), who, sizeof(who));
            DiagSink::write("ART-ARG %p -> \"\" caller=%s", (const void *) a1, who);
        }
        return g_orig(a0, "");
    }
    return g_orig(a0, a1);
}

}  // namespace

namespace ArtGuard {

void install() {
    if (g_on.load()) return;

    dl_iterate_phdr(phdrCb, nullptr);
    if (!g_art.found) {
        DiagSink::write("ART-GUARD libart/eh_frame not found");
        return;
    }

    const uintptr_t site = g_art.base + kCallSiteOff;
    const uintptr_t fn = findFuncStart(site);
    if (fn == 0) {
        DiagSink::write("ART-GUARD fde lookup failed site=libart.so+0x%lx",
                        (unsigned long) kCallSiteOff);
        return;
    }
    if (!looksLikePrologue(fn)) {
        DiagSink::write("ART-GUARD bad prologue fn=libart.so+0x%lx insn=%08x",
                        (unsigned long) (fn - g_art.base), *(const uint32_t *) fn);
        return;
    }

    void *origin = nullptr;
    if (DobbyHook((void *) fn, (void *) my_entry, &origin) != 0 || origin == nullptr) {
        DiagSink::write("ART-GUARD hook failed fn=libart.so+0x%lx",
                        (unsigned long) (fn - g_art.base));
        return;
    }
    g_orig = (entry_t) origin;
    g_on.store(true);

    DiagSink::write("ART-GUARD site=libart.so+0x%lx fn=libart.so+0x%lx hooked=1",
                    (unsigned long) kCallSiteOff, (unsigned long) (fn - g_art.base));

    // 入口前几条指令一并记下来：下一轮对比就能确认挂的是不是那个函数
    char line[200];
    int n = snprintf(line, sizeof(line), "ART-INS +0x%lx -",
                     (unsigned long) (fn - g_art.base));
    for (int i = 0; i < 6 && n < (int) sizeof(line) - 12; i++) {
        n += snprintf(line + n, sizeof(line) - (size_t) n, " %08x",
                      *(const uint32_t *) (fn + (uintptr_t) (i * 4)));
    }
    DiagSink::write("%s", line);
}

}  // namespace ArtGuard

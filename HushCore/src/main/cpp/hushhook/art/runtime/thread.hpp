#pragma once

#include <pthread.h>

#include "common.hpp"

namespace hushhook::art {

class Thread {
    CREATE_FUNC_SYMBOL_ENTRY(Thread *, CurrentFromGdb) {
        if (CurrentFromGdbSym) [[likely]]
            return CurrentFromGdbSym();
        else
            return nullptr;
    }

    using CurrentFn = Thread *(*)();
    inline static CurrentFn CurrentSym = nullptr;

public:
    static Thread *Current() {
        if (CurrentFromGdbSym) [[likely]] return CurrentFromGdbSym();
        if (CurrentSym) return CurrentSym();
        // Android 16（API 36+）的 libart 会裁掉 CurrentFromGdb —— 它只在调试构建里保留。
        // bionic 的 TLS slot 7（TLS_SLOT_ART_THREAD_SELF）始终存着当前 art::Thread*，
        // AOSP 的 Thread::Current() 本身就是读这个槽位。
        void **tls = nullptr;
#if defined(__aarch64__)
        __asm__ __volatile__("mrs %0, tpidr_el0" : "=r"(tls));
#endif
        return tls != nullptr ? reinterpret_cast<Thread *>(tls[7]) : nullptr;
    }

    static bool Init(const HookHandler &handler) {
        // 两个符号都只是「快捷方式」：任一解析成功就用符号，都失败就走 TLS。
        // 不能因为 CurrentFromGdb 缺失就让整个引擎初始化失败。
        RETRIEVE_FUNC_SYMBOL(CurrentFromGdb, "_ZN3art6Thread14CurrentFromGdbEv");
        CurrentSym =
            reinterpret_cast<CurrentFn>(hushhook::Dlsym(handler, "_ZN3art6Thread7CurrentEv"));
        return true;
    }
};
}  // namespace hushhook::art

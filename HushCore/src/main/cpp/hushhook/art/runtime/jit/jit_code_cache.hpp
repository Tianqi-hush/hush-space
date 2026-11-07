#pragma once

#include "common.hpp"

namespace hushhook::art::jit {
class JitCodeCache {
    CREATE_MEM_FUNC_SYMBOL_ENTRY(void, MoveObsoleteMethod, JitCodeCache *thiz,
                                 ArtMethod *old_method, ArtMethod *new_method) {
        if (MoveObsoleteMethodSym) [[likely]] {
            MoveObsoleteMethodSym(thiz, old_method, new_method);
        } else {
            // fallback to set data
            new_method->SetData(old_method->GetData());
            old_method->SetData(nullptr);
        }
    }

    CREATE_MEM_HOOK_STUB_ENTRY("_ZN3art3jit12JitCodeCache19GarbageCollectCacheEPNS_6ThreadE", void,
                               GarbageCollectCache, (JitCodeCache * thiz, Thread *self), {
                                   auto movements = GetJitMovements();
                                   LOGD("Before jit cache gc, moving %zu hooked methods",
                                        movements.size());
                                   for (auto [target, backup] : movements) {
                                       MoveObsoleteMethod(thiz, target, backup);
                                   }
                                   backup(thiz, self);
                               });

public:
    /// JIT 这两项都只是「让已 hook 的方法不在 JIT GC 时被回收」的加强项。
    /// 从 Android 16/17（API 36/37）开始 libart 导出符号被大幅裁剪，
    /// MoveObsoleteMethod / GarbageCollectCache 经常整条消失。
    /// 这里绝不能因此让整个 Init 失败——否则表现就是「模块全部不加载」。
    static bool Init(const HookHandler &handler) {
        auto sdk_int = GetAndroidApiLevel();
        if (sdk_int >= __ANDROID_API_O__) [[likely]] {
            if (!RETRIEVE_MEM_FUNC_SYMBOL(
                    MoveObsoleteMethod,
                    "_ZN3art3jit12JitCodeCache18MoveObsoleteMethodEPNS_9ArtMethodES3_")) {
                LOGW("MoveObsoleteMethod not found, fallback to ArtMethod::SetData");
                WarnInitStage("jit-move-obsolete");
            }
        }
        if (sdk_int >= __ANDROID_API_N__) [[likely]] {
            if (!HookSyms(handler, GarbageCollectCache)) {
                LOGW("GarbageCollectCache not hooked, JIT gc may recycle hooked code");
                WarnInitStage("jit-gc");
            }
        }
        return true;
    }
};
}  // namespace hushhook::art::jit

#pragma once

#include "art_method.hpp"
#include "common.hpp"

namespace hushhook::art {

class Instrumentation {
    inline static ArtMethod *MaybeUseBackupMethod(ArtMethod *art_method, const void *quick_code) {
        if (auto backup = IsHooked(art_method); backup && art_method->GetEntryPoint() != quick_code)
            [[unlikely]] {
            LOGD("Propagate update method code %p for hooked method %s to its backup", quick_code,
                 art_method->PrettyMethod().c_str());
            return backup;
        }
        return art_method;
    }

    CREATE_MEM_HOOK_STUB_ENTRY(
        "_ZN3art15instrumentation15Instrumentation40UpdateMethodsCodeToInterpreterEntryPointEPNS_9ArtMethodE",
        void, UpdateMethodsCodeToInterpreterEntryPoint,
        (Instrumentation * thiz, ArtMethod *art_method), {
            if (IsDeoptimized(art_method)) {
                LOGV("skip update entrypoint on deoptimized method %s",
                     art_method->PrettyMethod(true).c_str());
                return;
            }
            backup(thiz, MaybeUseBackupMethod(art_method, nullptr));
        });

    CREATE_MEM_HOOK_STUB_ENTRY(
        "_ZN3art15instrumentation15Instrumentation21InitializeMethodsCodeEPNS_9ArtMethodEPKv", void,
        InitializeMethodsCode,
        (Instrumentation * thiz, ArtMethod *art_method, const void *quick_code), {
            if (IsDeoptimized(art_method)) {
                LOGV("skip update entrypoint on deoptimized method %s",
                     art_method->PrettyMethod(true).c_str());
                return;
            }
            backup(thiz, MaybeUseBackupMethod(art_method, quick_code), quick_code);
        });

public:
    static bool Init(JNIEnv *env, const HookHandler &handler) {
        int sdk_int = GetAndroidApiLevel();
        if (sdk_int >= __ANDROID_API_P__) [[likely]] {
            if (!HookSyms(handler, InitializeMethodsCode,
                          UpdateMethodsCodeToInterpreterEntryPoint)) {
                // 这两个 hook 只负责「hooked 方法的入口被 ART 重新算出来时跟上备份」。
                // API 36+ 上符号签名常变，缺了只会让 deopt 后的迁移不完整，
                // 不该把整个引擎判死。
                LOGW("Failed to hook Instrumentation, deoptimize may be incomplete");
                WarnInitStage("instrumentation");
            }
        }
        return true;
    }
};

}  // namespace hushhook::art

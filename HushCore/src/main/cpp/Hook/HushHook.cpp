#include "HushHook.h"

#include <dlfcn.h>
#include <thread>
#include <unistd.h>
#include <string>
#include <string_view>

#include "hushhook.hpp"
#include "SandHook/xdl.h"
#include "Dobby/dobby.h"
#include "DiagSink.h"

namespace {

    void *g_artHandle = nullptr;
    volatile bool g_ready = false;
    /** 初始化已经明确失败：Java 侧不必再等满 6 秒。 */
    volatile bool g_failed = false;
    JavaVM *g_vm = nullptr;

    /** 打开 /apex/.../libart.so 的 ELF 句柄（只打开一次）。 */
    void ensureArtHandle() {
        if (g_artHandle != nullptr) return;
        static const char *kCandidates[] = {
                "/apex/com.android.art/lib64/libart.so",
                "/apex/com.android.art/lib/libart.so",
                "libart.so",
        };
        for (const char *p : kCandidates) {
            g_artHandle = xdl_open(p, XDL_DEFAULT);
            if (g_artHandle != nullptr) break;
        }
    }

    /**
     * HushHook 需要从 libart.so 里拿到那些「没有导出」的符号
     * （ArtMethod::RegisterNative、Instrumentation::Deoptimize 之类），
     * 这些只在 .dynsym / .symtab / .gnu_debugdata 里，dlsym 找不着。
     * xDL 三种表都解析，正好对上。
     */
    void *artResolver(std::string_view name) {
        ensureArtHandle();
        if (g_artHandle == nullptr) return nullptr;
        std::string sym(name);
        void *p = xdl_sym(g_artHandle, sym.c_str(), nullptr);
        if (p == nullptr) {
            // .dynsym 里没有就翻 .symtab（含 .gnu_debugdata），再不行问 linker
            p = xdl_dsym(g_artHandle, sym.c_str(), nullptr);
        }
        if (p == nullptr) {
            p = dlsym(RTLD_DEFAULT, sym.c_str());
        }
        return p;
    }

    /**
     * 前缀解析。HushHook 的 InitInfo 要求这一项非空（缺了直接 "Invalid init info"），
     * 而且 ART 里确实有两个符号会因 LTO / 链接变体带后缀：
     *   art::instrumentation::Instrumentation::GetOptimizedCodeFor
     *   art::DexFile_setTrusted
     * 精确名查不到时必须按前缀再兜一次。
     */
    void *artPrefixResolver(std::string_view prefix) {
        ensureArtHandle();
        if (g_artHandle == nullptr) return nullptr;
        std::string s(prefix);
        return xdl_sym_prefix(g_artHandle, s.c_str(), nullptr);
    }

    void *inlineHook(void *target, void *hooker) {
        void *backup = nullptr;
        if (DobbyHook(target, hooker, &backup) == 0) return backup;
        return nullptr;
    }

    bool inlineUnhook(void *func) {
        return DobbyDestroy(func) == 0;
    }

    /** 引擎内部哪个阶段失败，直接写进诊断日志。 */
    void onInitStage(const char *stage) {
        DiagSink::write("HushHook 失败阶段: %s", stage);
    }

    /** 可选能力缺失（JIT / Instrumentation / debuggable），只记录，不阻断。 */
    void onInitWarn(const char *stage) {
        DiagSink::write("HushHook 降级: %s", stage);
    }

    /** 初始化失败时把关键符号的解析结果打出来，省掉一轮试错。 */
    void dumpArtSymbols() {
        ensureArtHandle();
        if (g_artHandle != nullptr) {
            char diag[256];
            if (xdl_diag(g_artHandle, diag, sizeof(diag)) > 0) {
                DiagSink::write("HushHook diag %s", diag);
            }
        }
        // 2.4.6：换成引擎真正依赖的符号。旧清单里那条
        // ArtMethod::RegisterNative(ArtMethod*, void*) 在当前 ART 上本来就不存在，
        // 打出来只会误导；CurrentFromGdb 才是 API 36+ 真正被裁掉的那个。
        static const char *kProbe[] = {
                "_ZN3art6Thread14CurrentFromGdbEv",
                "_ZN3art6Thread7CurrentEv",
                "_ZN3art9ArtMethod14RegisterNativeEPKv",
                "_ZN3art9ArtMethod14RegisterNativeEPKvb",
                "_ZN3art6mirror9ArtMethod14RegisterNativeEPNS_6ThreadEPKvb",
                "_ZN3art11ClassLinker14RegisterNativeEPNS_6ThreadEPNS_9ArtMethodEPKv",
                "_ZN3art15instrumentation15Instrumentation19GetOptimizedCodeForEPNS_9ArtMethodE",
                "_ZN3art15instrumentation15Instrumentation21InitializeMethodsCodeEPNS_9ArtMethodEPKv",
                "_ZN3art15instrumentation15Instrumentation40UpdateMethodsCodeToInterpreterEntryPointEPNS_9ArtMethodE",
                "_ZN3art3jit12JitCodeCache18MoveObsoleteMethodEPNS_9ArtMethodES3_",
                "_ZN3art3jit12JitCodeCache19GarbageCollectCacheEPNS_6ThreadE",
                "_ZN3art7Runtime9instance_E",
                "_ZN3art7Runtime17SetJavaDebuggableEb",
                "_ZN3artL18DexFile_setTrustedEP7_JNIEnvP7_jclassP8_jobject",
        };
        bool anyExact = false;
        for (const char *n : kProbe) {
            std::string_view v(n);
            void *exact = artResolver(v);
            void *pref = exact ? nullptr : artPrefixResolver(v);
            if (exact != nullptr) anyExact = true;
            DiagSink::write("HushHook sym %.40s exact=%p prefix=%p", n, exact, pref);
        }
        // 精确名一个都没命中，说明 API 37 的 mangled name 与预期不同，
        // 直接把 libart 里相关的导出名打出来，下一轮照着改。
        if (!anyExact && g_artHandle != nullptr) {
            char buf[512];
            if (xdl_list_syms(g_artHandle, "ArtMethod", buf, sizeof(buf), 5) > 0) {
                DiagSink::write("HushHook names ArtMethod: %s", buf);
            }
            if (xdl_list_syms(g_artHandle, "DexFile", buf, sizeof(buf), 5) > 0) {
                DiagSink::write("HushHook names DexFile: %s", buf);
            }
        }
    }
}

void HushHook::setVm(JavaVM *vm) {
    g_vm = vm;
}

void HushHook::startAsyncInit() {
    static volatile bool started = false;
    if (started) return;
    started = true;

    if (g_vm == nullptr) {
        DiagSink::write("HushHook async: no vm");
        return;
    }

    std::thread([] {
        // 等主线程把 System.load 走完、ClassLoader 的锁释放掉。
        usleep(120 * 1000);

        JNIEnv *env = nullptr;
        JavaVMAttachArgs args;
        args.version = JNI_VERSION_1_6;
        args.name = const_cast<char *>("HushHook-Init");
        args.group = nullptr;
        if (g_vm->AttachCurrentThread(&env, &args) != JNI_OK || env == nullptr) {
            DiagSink::write("HushHook async: attach failed");
            return;
        }
        DiagSink::write("HushHook async: init begin");
        // 这个线程没有 Java 帧，hidden API 检查拿不到 caller，
        // 再加上 Java 侧已经 exemptAll 兜底，双重保险。
        install(env);
        DiagSink::write("HushHook async: init end ready=%d", (int) g_ready);
        g_vm->DetachCurrentThread();
    }).detach();
}

bool HushHook::isReady() {
    return g_ready;
}

bool HushHook::hasFailed() {
    return g_failed && !g_ready;
}

bool HushHook::install(JNIEnv *env) {
    if (g_ready) return true;
    if (env == nullptr) return false;

    hushhook::InitInfo info;
    info.inline_hooker = inlineHook;
    info.inline_unhooker = inlineUnhook;
    info.art_symbol_resolver = artResolver;
    info.art_symbol_prefix_resolver = artPrefixResolver;
    info.generated_class_name = "HushHooker_";
    info.generated_source_name = "Hush";
    info.generated_field_name = "hooker";
    info.generated_method_name = "{target}";

    // 每次都打一遍 libart 的 dynsym 统计：init 成功时也要看得到，
    // 否则分不清「符号名不对」和「符号表压根没读到」。
    ensureArtHandle();
    if (g_artHandle != nullptr) {
        char diag[256];
        if (xdl_diag(g_artHandle, diag, sizeof(diag)) > 0) {
            DiagSink::write("HushHook pre %s", diag);
        }
    } else {
        DiagSink::write("HushHook pre art handle null");
    }

    hushhook::SetInitStageLogger(onInitStage);
    hushhook::SetInitWarnLogger(onInitWarn);
    g_ready = hushhook::Init(env, info);
    g_failed = !g_ready;
    DiagSink::write("HushHook init=%d art=%p", (int) g_ready, g_artHandle);
    if (!g_ready) {
        // 这两条是 Init 里最早会因为「找不到符号」而失败的地方，
        // 先自己解析一遍，直接告诉下一轮该往哪儿看。
        dumpArtSymbols();
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    return g_ready;
}

jobject HushHook::hook(JNIEnv *env, jobject target, jobject hooker, jobject callback) {
    if (!g_ready || env == nullptr || target == nullptr || hooker == nullptr || callback == nullptr) {
        return nullptr;
    }
    jobject backup = hushhook::Hook(env, target, hooker, callback);
    DiagSink::write("HushHook hook %p -> backup=%p", target, backup);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    return backup;
}

bool HushHook::unhook(JNIEnv *env, jobject target) {
    if (!g_ready || env == nullptr || target == nullptr) return false;
    return hushhook::UnHook(env, target);
}

#include "RuntimeHook.h"
#import "JniHook/JniHook.h"
#include "BoxCore.h"
#include "DiagSink.h"

#include <dlfcn.h>
#include <unistd.h>
#include <cstring>
#include <string>

// =============================================================================
// so 加载链路。
//
// 加固壳（易盾）启动后要 dlopen 一长串自己的 so：libunisec、libBootloader、
// libnss* 等等。VirtualApp 容器里 nativeLibraryDir 指向沙盒目录，linker 的
// namespace 未必认这个路径；加载失败之后壳拿不到符号，只能走错误分支 ——
// 而这条分支正是之前 0xfffff9 的来源之一。
//
// 这里做两件事：
//   1. 把每次加载的结果（成功 / 失败原因）写进 diag.log，让失败点可见；
//   2. 失败时按 basename 去沙盒 lib 目录找同名 so，用绝对路径再 dlopen 一次，
//      绕过 namespace 的搜索路径限制。
// =============================================================================

/** 向 Java 侧要某个 so 在沙盒里的绝对路径；拿不到返回空串。 */
static std::string resolveGuestLib(const char *soName) {
    if (soName == nullptr || soName[0] == '\0') return std::string();
    JNIEnv *env = BoxCore::getEnv();
    if (env == nullptr) return std::string();
    jstring ret = BoxCore::resolveLibPath(env, soName);
    if (ret == nullptr) return std::string();
    const char *c = env->GetStringUTFChars(ret, nullptr);
    std::string out = (c == nullptr) ? std::string() : std::string(c);
    if (c != nullptr) env->ReleaseStringUTFChars(ret, c);
    env->DeleteLocalRef(ret);
    return out;
}

/**
 * nativeLoad 的返回值语义：成功返回 null，失败返回一段描述错误的字符串。
 * 统一在这里记录结果，失败时尝试绝对路径兜底。
 */
static jstring handleResult(JNIEnv *env, jstring name, jstring result,
                            const char *where, bool retry) {
    const char *nameC = (name == nullptr) ? nullptr : env->GetStringUTFChars(name, nullptr);
    std::string soName = (nameC == nullptr) ? std::string() : std::string(nameC);
    if (nameC != nullptr) env->ReleaseStringUTFChars(name, nameC);

    if (result == nullptr) {
        DiagSink::write("SO ok %s (%s)", soName.c_str(), where);
        return result;
    }

    const char *err = env->GetStringUTFChars(result, nullptr);
    DiagSink::write("SO FAIL %s (%s) -> %s", soName.c_str(), where,
                    err == nullptr ? "?" : err);
    if (err != nullptr) env->ReleaseStringUTFChars(result, err);

    if (!retry || soName.empty()) return result;

    std::string abs = resolveGuestLib(soName.c_str());
    if (abs.empty()) {
        DiagSink::write("SO no-sandbox-candidate %s", soName.c_str());
        return result;
    }

    void *h = dlopen(abs.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (h != nullptr) {
        DiagSink::write("SO recovered %s via %s", soName.c_str(), abs.c_str());
        return nullptr;
    }
    const char *de = dlerror();
    DiagSink::write("SO recover-FAILED %s (%s) dlerror=%s", soName.c_str(),
                    abs.c_str(), de == nullptr ? "?" : de);
    return result;
}

HOOK_JNI(jstring, nativeLoad, JNIEnv *env, jobject obj, jstring name, jobject class_loader) {
    jstring result = orig_nativeLoad(env, obj, name, class_loader);
    return handleResult(env, name, result, "nativeLoad", true);
}

HOOK_JNI(jstring, nativeLoad2, JNIEnv *env, jobject obj, jstring name, jobject class_loader,
         jobject caller) {
    jstring result = orig_nativeLoad2(env, obj, name, class_loader, caller);
    return handleResult(env, name, result, "nativeLoad2", true);
}

/**
 * System.load(绝对路径) 走 Runtime.load0(fromClass, filename)。
 *
 * 这里只观测、不改写路径。原因有两条：
 *   1. 判断路径是否存在得靠 access()，而 access 本身已被 IO hook 接管，
 *      拿到的结果是被重定向后的，不能代表真实文件状态；
 *   2. dlopen 走 linker 的 mmap，不经过 open/openat，IO 重定向对它无效，
 *      改写路径反而更容易把能加载的 so 改坏。
 * 真正需要兜底的是 nativeLoad（那里有明确的成功/失败返回值），见 handleResult。
 */
HOOK_JNI(void, load0, JNIEnv *env, jclass clazz, jobject fromClass, jstring filename) {
    if (filename != nullptr) {
        const char *path = env->GetStringUTFChars(filename, nullptr);
        if (path != nullptr) {
            DiagSink::write("SO load0 %s", path);
            env->ReleaseStringUTFChars(filename, path);
        }
    }
    orig_load0(env, clazz, fromClass, filename);
}

void RuntimeHook::init(JNIEnv *env) {
    const char *className = "java/lang/Runtime";
    if (BoxCore::getApiLevel() >= __ANDROID_API_Q__) {
        JniHook::HookJniFun(env, className, "nativeLoad",
                            "(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;",
                            (void *) new_nativeLoad2, (void **) (&orig_nativeLoad2), true);
    } else {
        JniHook::HookJniFun(env, className, "nativeLoad",
                            "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/String;",
                            (void *) new_nativeLoad, (void **) (&orig_nativeLoad), true);
    }

    JniHook::HookJniFun(env, className, "load0", "(Ljava/lang/Class;Ljava/lang/String;)V",
                        (void *) new_load0, (void **) (&orig_load0), true);
    DiagSink::write("SO RuntimeHook installed (nativeLoad + load0)");
}

#include "BoxCore.h"
#include "Log.h"
#include "IO.h"
#include <jni.h>
#include "JniHook/JniHook.h"
#include "Hook/VMClassLoaderHook.h"
#include "Hook/UnixFileSystemHook.h"
#include <Hook/BinderHook.h>
#include <Hook/DexFileHook.h>
#include "Hook/RuntimeHook.h"
#include "Hook/HushHook.h"
#include "Hook/DiagSink.h"
#include "Hook/NativeIOHook.h"
#include "Hook/ExitTrace.h"
#include "Hook/RuntimeHook.h"
#include "Hook/JniGuard.h"
#include "Hook/StrGuard.h"
#include "Hook/ArtGuard.h"
#include "Hook/ArtJniIdHook.h"
#include "Hook/JniEnvGuard.h"
#include "Hook/SurfaceProbe.h"
#include "Hook/Dobby/dobby.h"
#include "SandHook/oxorany.h"
#include "SandHook/xdl.h"
#include "VaultCore.h"
#include <sys/system_properties.h>
#include <sys/prctl.h>
#include <dlfcn.h>
#include <cstring>
#include <cstdio>
#include <string>
#include <sys/types.h>
#include <signal.h>
#include <cerrno>
#include <fcntl.h>
#include <unistd.h>

extern jlong n_challenge(JNIEnv*e,jclass,jobject ctx,jstring jd,jlong nonce);
extern jint n_verify(JNIEnv*e,jclass cls,jobject ctx,jstring jd,jlong nonce,jlong resp);

static void checkActivationOrExit(JNIEnv *env) {
    // Neutralized - offline standalone
    return;
}

struct {
    JavaVM *vm;
    jclass NativeCoreClass;
    jmethodID getCallingUidId;
    jmethodID redirectPathString;
    jmethodID redirectPathFile;
    jmethodID resolveLibPathId;
    int api_level;
    // Cached host package name for proc filtering
    char hostPkg[256];
    bool hostPkgSet;
} VMEnv;

/** diag.log 的绝对路径，由 Java 侧在 enableIO 之前传入。 */
static char g_diagPath[512] = {0};

/** 旧的坏指针补丁组开关（默认关）。见 enableIO 里的说明。 */
static bool g_legacyGuards = false;

/**
 * ART 层 hook 总闸，默认**关**。
 *
 * enableIO 里原来无条件跑 HushHook::startAsyncInit()，也就是每个 guest
 * 进程（包括完全没有校验需求的普通 App）都会在后台对 libart 做 inline hook。
 * HushHook 的 Init 要走 SuspendAll、要改 ART 内部结构，任何一处和这台机器的
 * ART 版本不匹配，表现就是 App 能进页面然后莫名退出 —— 这个代价不该由
 * 普通 App 承担。
 *
 * 只有 Java 侧 HookPolicy 判定为「白名单壳」时才由 setHookGate(true) 打开，
 * 其余进程连 HushHook 的 Init 都不跑。
 */
static bool g_hookGate = false;

void setHookGate(JNIEnv *env, jclass clazz, jboolean on) {
    g_hookGate = (on == JNI_TRUE);
    __android_log_print(ANDROID_LOG_INFO, "HushCore", "hookGate=%d", g_hookGate ? 1 : 0);
}


JNIEnv *getEnv() {
    JNIEnv *env;
    VMEnv.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    return env;
}

JNIEnv *ensureEnvCreated() {
    JNIEnv *env = getEnv();
    if (env == NULL) {
        VMEnv.vm->AttachCurrentThread(&env, NULL);
    }
    return env;
}

int BoxCore::getCallingUid(JNIEnv *env, int orig) {
    env = ensureEnvCreated();
    return env->CallStaticIntMethod(VMEnv.NativeCoreClass, VMEnv.getCallingUidId, orig);
}

jstring BoxCore::redirectPathString(JNIEnv *env, jstring path) {
    env = ensureEnvCreated();
    return (jstring) env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.redirectPathString, path);
}

jobject BoxCore::redirectPathFile(JNIEnv *env, jobject path) {
    env = ensureEnvCreated();
    return env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.redirectPathFile, path);
}

JNIEnv *BoxCore::getEnv() {
    if (VMEnv.vm == nullptr) return nullptr;
    return ensureEnvCreated();
}

jstring BoxCore::resolveLibPath(JNIEnv *env, const char *soName) {
    if (soName == nullptr || VMEnv.resolveLibPathId == nullptr) return nullptr;
    env = ensureEnvCreated();
    if (env == nullptr) return nullptr;
    jstring arg = env->NewStringUTF(soName);
    if (arg == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }
    jstring ret = (jstring) env->CallStaticObjectMethod(
            VMEnv.NativeCoreClass, VMEnv.resolveLibPathId, arg);
    env->DeleteLocalRef(arg);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return ret;
}

int BoxCore::getApiLevel() {
    return VMEnv.api_level;
}

JavaVM *BoxCore::getJavaVM() {
    return VMEnv.vm;
}

// =============================================================================
// __system_property_get hook — intercepts native property reads
// Anti-cheat SDKs read ro.build.*, ro.serialno, etc. via native property API
// bypassing Build.* fields and java SystemProperties class entirely.
// =============================================================================

// Original function pointer
static int (*orig_system_property_get)(const char *name, char *value) = nullptr;

// Java callback for property spoofing
static jclass sSysPropHookClass = nullptr;
static jmethodID sInterceptPropertyMethod = nullptr;
static bool sPropertyHookInstalled = false;

static int hooked_system_property_get(const char *name, char *value) {
    // Call original first to get the real value
    int len = 0;
    if (orig_system_property_get) {
        len = orig_system_property_get(name, value);
    }
    
    // Only intercept known detection-relevant properties
    if (name == nullptr || !sPropertyHookInstalled) return len;
    
    // Quick prefix check to avoid JNI overhead on irrelevant properties
    if (strncmp(name, "ro.", 3) != 0 && 
        strncmp(name, "sys.", 4) != 0 &&
        strncmp(name, "persist.", 8) != 0 &&
        strncmp(name, "init.", 5) != 0 &&
        strncmp(name, "service.", 8) != 0 &&
        strncmp(name, "ril.", 4) != 0) {
        return len;
    }
    
    // Call Java-side interceptor
    //
    // 这里绝不能用 ensureEnvCreated()：它会给未 attach 的 native 线程
    // AttachCurrentThread，而 __system_property_get 可能被 ART 的 GC /
    // 编译器线程调用，attach 过程本身又会读属性、又要拿锁，直接递归死锁。
    // 只处理已经在 Java 世界里的线程，native 线程原样返回。
    JNIEnv *env = nullptr;
    if (VMEnv.vm == nullptr ||
        VMEnv.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK ||
        env == nullptr) {
        return len;
    }
    if (sSysPropHookClass == nullptr) return len;
    
    jstring jName = env->NewStringUTF(name);
    jstring jOrigValue = env->NewStringUTF(value);
    
    jstring jResult = (jstring) env->CallStaticObjectMethod(
        sSysPropHookClass, sInterceptPropertyMethod, jName, jOrigValue);

    // Java 侧任何异常都必须当场清掉：这个函数可能被 ART/GC 线程调用，
    // 挂起的 pending exception 会让下一次 JNI 调用直接失败。
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(jName);
        env->DeleteLocalRef(jOrigValue);
        if (jResult != nullptr) env->DeleteLocalRef(jResult);
        return len;
    }

    if (jResult != nullptr && jResult != jOrigValue) {
        const char *resultStr = env->GetStringUTFChars(jResult, nullptr);
        if (resultStr != nullptr) {
            len = strlen(resultStr);
            strncpy(value, resultStr, PROP_VALUE_MAX - 1);
            value[PROP_VALUE_MAX - 1] = '\0';
            env->ReleaseStringUTFChars(jResult, resultStr);
        }
    }
    
    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(jOrigValue);
    if (jResult != nullptr) env->DeleteLocalRef(jResult);
    
    return len;
}

static void installPropertyHook(JNIEnv *env) {
    if (sPropertyHookInstalled) return;
    // Find the SystemPropertyHook Java class
    jclass clazz = env->FindClass("com/hush/fake/service/SystemPropertyHook");
    if (clazz == nullptr) {
        env->ExceptionClear();
        ALOGD("SystemPropertyHook class not found, skipping native property hook");
        return;
    }
    
    sSysPropHookClass = (jclass) env->NewGlobalRef(clazz);
    sInterceptPropertyMethod = env->GetStaticMethodID(
        sSysPropHookClass, "interceptProperty",
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    
    if (sInterceptPropertyMethod == nullptr) {
        env->ExceptionClear();
        ALOGD("interceptProperty method not found, skipping native property hook");
        return;
    }
    
    // 取原函数地址。dlsym 拿到的就是 libc 导出的入口，Dobby 直接改写它的
    // 前几条指令，所有调用方（Java 反射 SystemProperties、native dlopen 的
    // so、加固壳自己的 __system_property_get）都会走到 hooked_*。
    void *target = nullptr;
    void *libc = dlopen("libc.so", RTLD_NOW);
    if (libc != nullptr) {
        target = dlsym(libc, "__system_property_get");
        dlclose(libc);
    }
    if (target == nullptr) {
        target = (void *) &__system_property_get;
    }

    void *orig = nullptr;
    int rc = DobbyHook(target, (void *) hooked_system_property_get, &orig);
    if (rc != 0 || orig == nullptr) {
        ALOGD("DobbyHook __system_property_get failed rc=%d", rc);
        return;
    }
    orig_system_property_get = (int (*)(const char *, char *)) orig;

    // 预热一次：SystemPropertyHook 的类初始化必须在主线程完成。
    // 如果拖到第一次属性读取（可能发生在 GC/编译器线程）再初始化，
    // 会在 ART 持锁时回调 Java，直接死锁。
    jstring warmKey = env->NewStringUTF("ro.hush.warmup");
    jstring warmVal = env->NewStringUTF("");
    jobject warmRet = env->CallStaticObjectMethod(
            sSysPropHookClass, sInterceptPropertyMethod, warmKey, warmVal);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (warmRet != nullptr) env->DeleteLocalRef(warmRet);
    env->DeleteLocalRef(warmKey);
    env->DeleteLocalRef(warmVal);

    sPropertyHookInstalled = true;
    ALOGD("Native property hook installed (target=%p orig=%p)", target, orig);
}

// =============================================================================
// Native hook initialization
// =============================================================================

void nativeHook(JNIEnv *env) {
    BaseHook::init(env);
    // __system_property_get 覆盖必须在所有 guest 上生效，不受 hookGate 限制：
    // 设备伪装（ro.product.*、ro.build.version.emui 等）只改 Java 的 Build
    // 字段是不够的，华为/荣耀的账号服务、加固壳、反作弊全都直接读属性。
    // 2.6.9：**不再 inline hook libc 的 __system_property_get。**
    //
    // 加固壳同样会校验 libc 代码段的字节。改一个 5 字节跳转，它一次
    // memcmp 就能发现；发现之后不会报错，而是自己把栈 canary 改掉再
    // abort，日志上看起来像"栈溢出"，实际是反调试自杀。
    // 设备伪装里的属性覆盖因此失效，但 Build.* 那一层仍然生效，
    // 用「机型/品牌/序列号」识别环境的游戏不受影响。
    // installPropertyHook(env);
    UnixFileSystemHook::init(env);
    VMClassLoaderHook::init(env);
    BinderHook::init(env);
    DexFileHook::init(env);
    // RuntimeHook（nativeLoad / load0）暂时不启用。
    // 它靠 RegisterNatives 重注册入口，orig 指针依赖 art_method_native_offset，
    // 在 API 37 上万一算偏，拿到的就是野指针 —— 而 nativeLoad 是所有 so 加载的
    // 必经之路，挂掉等于整个应用起不来。诊断价值用主线程栈采样已经能覆盖
    // （so 加载失败会以 UnsatisfiedLinkError 出现在 Java 栈里），不值得冒这个险。
    // 需要时把下面一行打开，并先确认 nativeIoDiag 里 art 偏移正常。
    if (g_legacyGuards) {
        RuntimeHook::init(env);
    }
}

void hideXposed(JNIEnv *env, jclass clazz) {
    checkActivationOrExit(env);
    ALOGD("set hideXposed");
    VMClassLoaderHook::hideXposed();
}

void init(JNIEnv *env, jobject clazz, jint api_level) {
    ALOGD("NativeCore init.");
    VMEnv.api_level = api_level;
    VMEnv.hostPkgSet = false;
    memset(VMEnv.hostPkg, 0, sizeof(VMEnv.hostPkg));
    VMEnv.NativeCoreClass = (jclass) env->NewGlobalRef(env->FindClass(VMCORE_CLASS));
    VMEnv.getCallingUidId = env->GetStaticMethodID(VMEnv.NativeCoreClass, "getCallingUid", "(I)I");
    VMEnv.redirectPathString = env->GetStaticMethodID(VMEnv.NativeCoreClass, "redirectPath","(Ljava/lang/String;)Ljava/lang/String;");
    VMEnv.redirectPathFile = env->GetStaticMethodID(VMEnv.NativeCoreClass, "redirectPath","(Ljava/io/File;)Ljava/io/File;");
    VMEnv.resolveLibPathId = env->GetStaticMethodID(VMEnv.NativeCoreClass, "resolveLibPath","(Ljava/lang/String;)Ljava/lang/String;");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        VMEnv.resolveLibPathId = nullptr;
    }
    JniHook::InitJniHook(env, api_level);
    
    // 2.6.9：属性 inline hook 已停用（见 nativeHook 里的说明）。
    // installPropertyHook(env);
}

void addIORule(JNIEnv *env, jclass clazz, jstring target_path, jstring relocate_path) {
    checkActivationOrExit(env);
    ALOGD("set addIORule");
    IO::addRule(env->GetStringUTFChars(target_path, JNI_FALSE),env->GetStringUTFChars(relocate_path, JNI_FALSE));
}

void enableIO(JNIEnv *env, jclass clazz) {
    checkActivationOrExit(env);
    ALOGD("set enableIO");
    IO::init(env);
    // ---------------------------------------------------------------------
    // 普通 App（不在白名单里）在这一层必须和 1.0 完全一致：
    //   IO::init + nativeHook 之后什么都不装。
    //
    // 下面这三样都是我为「光遇崩溃」加的诊断/兼容层，2026-11-01 之后成了
    // 普通 App 闪退的来源：
    //
    //   ExitTrace   对 libc 的 exit/_exit/abort/kill/tgkill/**syscall** 做 inline
    //               hook，并接管 SIGSEGV/ABRT/ILL/BUS/FPE/TRAP 六个致命信号。
    //               代价有三：syscall() 是所有系统调用的公共入口，被 Dobby 改写
    //               后每条系统调用都要多绕一趟；ART 的隐式空指针检查本来就靠
    //               SIGSEGV 驱动，被我们插一层处理器后语义就变了；tgkill 是 ART
    //               挂起线程用的信号，hook 里做任何写日志的动作都会拖慢 GC。
    //               它只是诊断工具，不该出现在任何 App 的常规路径上。
    //
    //   NativeIO    对 libc 的 open/openat/stat/readlink/opendir/mkdir/rename/
    //               chmod/truncate 等 22 个符号做 inline hook。普通 App 的文件
    //               访问全部走 java.io，Java 层 UnixFileSystem 重定向已经够用，
    //               不需要在 libc 这一层再改一遍语义。只有 Unity / 加固壳这种
    //               直接在 C 里 open("/data/data/<pkg>/...") 的才需要。
    //
    //   HushHookInit 对 libart 做 inline hook，走 SuspendAll。
    //
    // 三者都由 setHookGate() 控制，门在 HookPolicy 白名单里，当前只有光遇。
    // ---------------------------------------------------------------------
    if (g_hookGate) {
        // 退出追踪要在重定向生效前用真实路径打开日志 fd。
        ExitTrace::install(g_diagPath);
        // HushHook 初始化交给后台线程：主线程这次调用会立刻返回，
        // 回到 Java 层后用 Thread.sleep 轮询 isHookReady()。
        HushHook::startAsyncInit();
    } else {
        ALOGD("hookGate off, skip exit-trace / HushHook / libc-io hooks");
    }
    nativeHook(env);
    if (g_hookGate) {
        // Java 层重定向（java.io.UnixFileSystem）之后再加一层 libc hook：
        // 加固壳和 Unity 全都在 C 层直接 open/openat，走不到 Java 那层。
        NativeIO::install();
    }

    // ---------------------------------------------------------------------
    // 已下线：JniGuard / StrGuard / ArtGuard
    //
    // 这三层是围绕「坏指针 0xfffff9」修的补丁，思路是从三处把非法指针兜住。
    // 但它们都靠 Dobby 改写 libc / libart 的函数入口，等价于在系统库的代码段
    // 里打洞：一旦壳做了自身或运行时的完整性校验，反而会被判定为注入。
    // 更关键的是它们从未真正解决问题 —— 拦掉 strlen 之后，同一个坏值继续在
    // 下游被解引用，崩溃点只是从 strlen 挪到了 addr=0x10。
    //
    // 真正的根因已经查明：外部存储沙盒根落在 /storage/emulated/0/SdCard，
    // 分区存储下不可写，壳读不到 ntunisdk_game_config 后返回错误码并被当作
    // 指针使用（-7 -> 0xfffff9）。修 BEnvironment 即可，无需这些补丁。
    // 保留源码，需要时打开下面的开关即可复现诊断。
    // ---------------------------------------------------------------------
    if (g_legacyGuards) {
        JniGuard::install(env);
        StrGuard::install();
        ArtGuard::install();
    }
    DiagSink::write("HushHook ready=%d", (int) HushHook::isReady());
}

// ---------------------------------------------------------------------------
// JNI ID 形态探针
//
// Android 14 起 ART 引入 JniIdManager：jmethodID 有可能不再是 ArtMethod*，
// 而是编码后的 uint64。加固壳若把 jmethodID 当指针直接解引用，就会读到垃圾，
// 表现为「小整数地址 + strlen 崩溃」。
//
// 这一版只报告、不修改：先把三个事实打出来——
//   1. FromReflectedMethod 返回的是指针还是小整数
//   2. libart 里有没有 EncodeGenericId<ArtMethod>/<ArtField> 这个导出符号
//   3. 有没有对应的 DecodeGenericId（决定 hook 之后会不会把解码路径带崩）
// ---------------------------------------------------------------------------
jstring probeJniId(JNIEnv *env, jclass clazz, jobject method) {
    jmethodID mid = nullptr;
    if (method != nullptr) {
        mid = env->FromReflectedMethod(method);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    unsigned long long v = (unsigned long long) (uintptr_t) mid;

    void *art = xdl_open("/apex/com.android.art/lib64/libart.so", XDL_DEFAULT);
    void *encM = nullptr, *encF = nullptr, *decM = nullptr, *decF = nullptr;
    char list[640];
    int listed = 0;
    if (art != nullptr) {
        encM = xdl_dsym(art, "_ZN3art3jni12JniIdManager15EncodeGenericIdINS_9ArtMethodEEEmNS_16ReflectiveHandleIT_EE", nullptr);
        encF = xdl_dsym(art, "_ZN3art3jni12JniIdManager15EncodeGenericIdINS_9ArtFieldEEEmNS_16ReflectiveHandleIT_EE", nullptr);
        // 名字长度必须是 15（DecodeGenericId），上一版手写成 14 导致 decM/decF 恒为 0。
        // 这里顺手把 ART 里真实的 GenericId 符号名列出来，名字再对不上也一眼能看出来。
        listed = xdl_list_syms(art, "GenericId", list, sizeof(list), 6);
        if (listed > 0) {
            decM = xdl_dsym(art, "_ZNK3art3jni12JniIdManager15DecodeGenericIdINS_9ArtMethodEEEPT_m", nullptr);
            decF = xdl_dsym(art, "_ZNK3art3jni12JniIdManager15DecodeGenericIdINS_9ArtFieldEEEPT_m", nullptr);
        } else {
            list[0] = '\0';
        }
    }

    char buf[1024];
    snprintf(buf, sizeof(buf),
             "mid=0x%llx looksPtr=%d encM=%d encF=%d decM=%d decF=%d syms(%d)=%s",
             v,
             // 上一版写的是 v > 0x100000000（4GB），而 ART 的 boot image 从
             // 0x12c00000 开始，这台机器上 mid=0x265c828 明明是合法 ArtMethod*，
             // 却被判成「不是指针」，把方向带偏了两版。按 64 位进程的实际情况，
             // 0x10000000 以上就只可能是指针。
             (v >= 0x10000000ULL && v <= 0x7fffffffffffULL) ? 1 : 0,
             encM != nullptr, encF != nullptr, decM != nullptr, decF != nullptr,
             listed, list);
    return env->NewStringUTF(buf);
}

/** 让 EncodeGenericId<ArtMethod> 直接返回 ArtMethod*，把 jmethodID 退回指针语义。 */
jstring installJniIdCompat(JNIEnv *env, jclass clazz) {
    // 1.6.16 的日志证明这条路走不通：DobbyHook 四个符号全部返回成功，
    // 但紧接着的真实往返里 encM=0 decM=0（HookEncM 一次都没被调用），
    // 说明 ART 内部的 Encode/Decode 被编译器内联，导出符号只是摆设。
    // 既然命中不了，它就只剩副作用：在 libart 里改了四条指令，任何路径
    // 意外走到那里都会换掉 jmethodID 的语义。直接停用。
    (void) env;
    (void) clazz;
    return env->NewStringUTF("disabled(1.6.17: symbols never hit)");
}

/**
 * JNI 函数表前置校验。光遇的壳在 GetMethodID 里传了非法 name 指针
 * （x0=0xfffff9），ART 直接 strlen 就崩，什么都留不下来。
 */
jstring installJniEnvGuard(JNIEnv *env, jclass clazz) {
    std::string r = JniEnvGuard::install(env);
    return env->NewStringUTF(r.c_str());
}

/**
 * EGL / ANativeWindow 观测。旋转后黑屏到底是「窗口没 resize」「EGLSurface 没重建」
 * 还是「渲染线程停了」，只有这一层能区分。
 */
jboolean installSurfaceProbe(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return SurfaceProbe::install() ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// ART 层 Java 方法 hook（HushHook）
//
// 容器自己的伪 PackageManager 只覆盖「走 Java 层」的调用；加固壳在 native 里
// 直接 CallObjectMethod 的那条链绕不过去，只能从 ART 换方法体。
// ---------------------------------------------------------------------------
jobject hookMethod(JNIEnv *env, jclass clazz, jobject target, jobject hooker, jobject callback) {
    return HushHook::hook(env, target, hooker, callback);
}

jboolean isHookReady(JNIEnv *env, jclass clazz) {
    return HushHook::isReady() ? JNI_TRUE : JNI_FALSE;
}

jboolean isHookFailed(JNIEnv *env, jclass clazz) {
    return HushHook::hasFailed() ? JNI_TRUE : JNI_FALSE;
}

/** 显式触发 HushHook 异步初始化（enableIO 里已经自动调过一次，这里是幂等的兜底）。 */
void startHookInit(JNIEnv *env, jclass clazz) {
    HushHook::startAsyncInit();
}

jboolean unhookMethod(JNIEnv *env, jclass clazz, jobject target) {
    return HushHook::unhook(env, target) ? JNI_TRUE : JNI_FALSE;
}

/** Java 侧在 enableIO 之前把 diag.log 的绝对路径传进来，供退出追踪写日志。 */
void setDiagPath(JNIEnv *env, jclass clazz, jstring path) {
    if (path == nullptr) return;
    const char *p = env->GetStringUTFChars(path, JNI_FALSE);
    if (p != nullptr) {
        strncpy(g_diagPath, p, sizeof(g_diagPath) - 1);
        g_diagPath[sizeof(g_diagPath) - 1] = '\0';
        env->ReleaseStringUTFChars(path, p);
    }
}

// Cache host package name for native-level filtering
void setHostPkg(JNIEnv *env, jclass clazz, jstring pkg) {    if (pkg != nullptr) {
        const char *pkgStr = env->GetStringUTFChars(pkg, JNI_FALSE);
        if (pkgStr != nullptr) {
            strncpy(VMEnv.hostPkg, pkgStr, sizeof(VMEnv.hostPkg) - 1);
            VMEnv.hostPkgSet = true;
            env->ReleaseStringUTFChars(pkg, pkgStr);
            ALOGD("Host package cached: %s", VMEnv.hostPkg);
        }
    }
}

/**
 * 容器里的 guest 进程默认是 non-dumpable 的。Linux 在 hidepid 的 /proc 上
 * 用 ptrace_may_access() 决定某个 pid 目录是否可见，non-dumpable 会让
 * 同 uid 的其他进程也看不到它 —— Star 的 native 注入器就是这样「找不到光遇」。
 * 把 dumpable 改回 1，同 uid 的注入器（main）才能在 /proc 里看到游戏进程。
 */
int setDumpable(JNIEnv *env, jclass clazz, jint value) {
    int old = prctl(PR_GET_DUMPABLE, 0, 0, 0, 0);
    if (prctl(PR_SET_DUMPABLE, (int) value, 0, 0, 0) != 0) {
        return -errno;
    }
    return old;
}

int getDumpable(JNIEnv *env, jclass clazz) {
    return prctl(PR_GET_DUMPABLE, 0, 0, 0, 0);
}

jboolean isNativeIoActive(JNIEnv *env, jclass clazz) {
    return NativeIO::enabled() ? JNI_TRUE : JNI_FALSE;
}

/**
 * 从 Java 侧发一次真实的 open 请求，用来确认 hook 是否改变了 native 的解析结果。
 * 走的是同一个 ::open，因此会命中我们自己挂上去的 inline hook。
 * 返回 0 表示打开成功，负数表示 -errno。
 */
jint probeOpen(JNIEnv *env, jclass clazz, jstring path) {
    if (path == nullptr)
        return -EINVAL;
    const char *p = env->GetStringUTFChars(path, JNI_FALSE);
    if (p == nullptr)
        return -EINVAL;
    int fd = ::open(p, O_RDONLY | O_CLOEXEC);
    int err = errno;
    env->ReleaseStringUTFChars(path, p);
    if (fd >= 0) {
        ::close(fd);
        return 0;
    }
    return -err;
}

/**
 * 把 native IO hook 的真实状态回传给 Java，由 Java 写进 diag.log。
 * native 的 ALOGD 只进 logcat，用户导出的日志看不到，所以必须走这条路。
 */
jstring nativeIoDiag(JNIEnv *env, jclass clazz, jstring path) {
    const char *p = (path == nullptr) ? nullptr : env->GetStringUTFChars(path, JNI_FALSE);
    const char *text = NativeIO::diag(p);
    if (p != nullptr) env->ReleaseStringUTFChars(path, p);
    return env->NewStringUTF(text);
}

/** 开关旧的 JniGuard/StrGuard/ArtGuard 三件套，仅用于排查回归。 */
void setLegacyGuards(JNIEnv *env, jclass clazz, jboolean on) {
    g_legacyGuards = (on == JNI_TRUE);
}

/**
 * 这张表必须与 VNative.java 里的 native 声明严格一一对应。
 *
 * 踩过的坑（1.4.0）：表里多写了一个 Java 侧不存在的 setLegacyGuards，
 * 而 ART 的 RegisterNatives 是顺序注册、遇到找不到的方法就整体返回 JNI_ERR，
 * 于是排在它后面的 init / enableIO / setDiagPath 全部没绑定。
 * 表现是 so "加载成功"、调用 init 时抛 UnsatisfiedLinkError，日志恰好停在
 * "before VNative.init" 那一行，看起来像崩在 native 里。
 */
static JNINativeMethod gMethods[] = {
        {"hideXposed", "()V",(void *) hideXposed},
        {"nativeIoDiag", "(Ljava/lang/String;)Ljava/lang/String;", (void *) nativeIoDiag},
        {"setDiagPath", "(Ljava/lang/String;)V", (void *) setDiagPath},
        {"addIORule",  "(Ljava/lang/String;Ljava/lang/String;)V", (void *) addIORule},
        {"enableIO",   "()V",(void *) enableIO},
        {"init","(I)V",(void *) init},
        {"setHostPkg", "(Ljava/lang/String;)V", (void *) setHostPkg},
        {"setDumpable", "(I)I", (void *) setDumpable},
        {"getDumpable", "()I", (void *) getDumpable},
        {"isNativeIoActive", "()Z", (void *) isNativeIoActive},
        {"probeOpen", "(Ljava/lang/String;)I", (void *) probeOpen},
        {"hookMethod", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/reflect/Method;)Ljava/lang/reflect/Method;", (void *) hookMethod},
        {"isHookReady", "()Z", (void *) isHookReady},
        {"isHookFailed", "()Z", (void *) isHookFailed},
        {"startHookInit", "()V", (void *) startHookInit},
        {"setHookGate", "(Z)V", (void *) setHookGate},
        {"probeJniId", "(Ljava/lang/reflect/Method;)Ljava/lang/String;", (void *) probeJniId},
        {"installJniIdCompat", "()Ljava/lang/String;", (void *) installJniIdCompat},
        {"installJniEnvGuard", "()Ljava/lang/String;", (void *) installJniEnvGuard},
        {"installSurfaceProbe", "()Z", (void *) installSurfaceProbe},
        {"unhookMethod", "(Ljava/lang/reflect/Method;)Z", (void *) unhookMethod},
};

/** 逐项注册：单项失败只影响它自己，不会连累后面的方法。 */
int registerNativeMethods(JNIEnv *env, const char *className,JNINativeMethod *gMethods, int numMethods) {
    jclass clazz = env->FindClass(className);
    if (clazz == nullptr) {
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, "HushCore",
                            "RegisterNatives: class %s not found", className);
        return JNI_FALSE;
    }
    int failed = 0;
    for (int i = 0; i < numMethods; i++) {
        if (env->RegisterNatives(clazz, &gMethods[i], 1) < 0) {
            env->ExceptionClear();
            failed++;
            __android_log_print(ANDROID_LOG_ERROR, "HushCore",
                                "RegisterNatives FAILED: %s.%s %s",
                                className, gMethods[i].name, gMethods[i].signature);
        }
    }
    env->DeleteLocalRef(clazz);
    return failed == 0 ? JNI_TRUE : JNI_FALSE;
}

int registerNatives(JNIEnv *env) {
    if (!registerNativeMethods(env, VMCORE_CLASS, gMethods,sizeof(gMethods) / sizeof(gMethods[0])))
        return JNI_FALSE;
    return JNI_TRUE;
}

int registerMethod(JNIEnv *jenv) {
    return registerNatives(jenv);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    VMEnv.vm = vm;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_EVERSION;
    }
    // 注册表不全就直接失败。宁可 System.load 当场报错，也不要让 so
    // "看起来加载成功"、实际上一半 native 方法没有实现。
    if (!registerMethod(env)) {
        __android_log_print(ANDROID_LOG_ERROR, "HushCore",
                            "JNI_OnLoad: RegisterNatives incomplete, refuse to load");
        return JNI_ERR;
    }
    VaultCore_register(env);
    // 这里只存 vm，不做 HushHook Init。
    // 在 JNI_OnLoad 里直接 Init 会死锁：那一刻主线程正站在 System.load 的
    // nativeLoad 里、持有 ClassLoader 的锁，而 HushHook 的 Init 要 SuspendAll
    // 再加载生成的 dex —— 主线程在 native 里不检查 safepoint，永远停不下来。
    // 表现为日志停在 System.load 之后彻底静默（连心跳都没有）。
    // 真正的 Init 交给 enableIO 起的后台线程。
    HushHook::setVm(vm);
    return JNI_VERSION_1_6;
}
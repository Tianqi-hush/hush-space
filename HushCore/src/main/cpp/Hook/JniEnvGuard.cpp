//
// JniEnvGuard.cpp
//
// ── 1.6.16 的教训：只改 JNI 函数表是拦不住的 ────────────────────────────
//
// 1.6.16 的日志（15:02:35.766）长这样：
//
//   SIGNAL 11 addr=0xffffe0 pc=libc.so+0x719b0(~strlen+0xf0) lr=libart.so+0x233984
//   SIGNAL-REG x0=0xfffff9 x1=0xffffe0
//   UW #03 libc.so+0x719b0
//   UW #04 libart.so+0x233984
//   UW #05 libart.so+0x2b71c0
//   UW #06 libart.so+0x688158
//   UW #07 libunisec.so+0x7f264      ← 壳里那条 blr 的返回地址
//   UW #08 libunisec.so+0x54900
//
// 同一次运行里 JNI-GUARD 明确装了：
//   JNI-GUARD installed tbl=0x721381b028 gm=0x7213287f50 ... low=0x10000000
// 而且 hook 本身是活的（同一次启动里打出了一百多条 JNI-GETMID）。
//
// 但这份崩溃栈里 **从 #03 到 #08 一帧 libHushCore.so 都没有**。
// 如果壳走的是 env->functions->GetMethodID，中间必然夹着 my_GetMethodID 的栈帧。
// 结论是确定的、不需要再猜：
//
//   壳没有走 JNI 函数表，而是自己解析出 libart 导出的
//   art::JNI::GetMethodID 地址（也就是表里存的那个 gm=0x7213287f50）直接调。
//   表被我们改过，但壳手里握的是原始地址，绕过去了。
//
// 所以这一版做两件事，缺一不可：
//
//   1) 对表项指向的**原始函数地址**做 Dobby inline hook。
//      这样「走表」和「直调原始地址」两条路都会进同一个 detour。
//   2) 表项照样替换。改造后 detour 转发用的是 Dobby 给的 trampoline，
//      不是原始入口，所以不会自己 hook 自己。
//
// ── 附带把「崩溃前壳到底在干什么」存下来 ──────────────────────────────
//
// 0xfffff9 = 0x100000000 - 7，也就是 32 位下的 -7。它出现在 name 位置，
// 说明壳把某个返回 -7 的函数的返回值当成了字符串指针。
// 光看一次崩溃看不出那个 -7 是哪来的，所以这里留一个 8 条的调用历史环，
// 崩溃时由信号处理器原样打出来：前几条正常调用的类名/方法名/lr，
// 就是壳在崩之前那几十毫秒里做的事情。
//

#include "JniEnvGuard.h"

#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>

#include "Dobby/dobby.h"
#include "DiagSink.h"

namespace {

bool g_installed = false;

using GetMethodId_t = jmethodID(JNICALL *)(JNIEnv *, jclass, const char *, const char *);
using GetFieldId_t = jfieldID(JNICALL *)(JNIEnv *, jclass, const char *, const char *);

/** 表项指向的原始函数（Dobby 的 trampoline，未 hook 成功时是原地址）。 */
GetMethodId_t o_gm = nullptr;
GetMethodId_t o_gsm = nullptr;
GetFieldId_t o_gf = nullptr;

/** 原始函数入口地址，仅用于日志和「到底 hook 没 hook 上」的判断。 */
void *g_addrGM = nullptr;
void *g_addrGSM = nullptr;
void *g_addrGF = nullptr;
bool g_dobbyGM = false;
bool g_dobbyGSM = false;
bool g_dobbyGF = false;

JNINativeInterface *g_tbl = nullptr;

std::atomic<int> g_okLog{1200};
std::atomic<int> g_badLog{40};
std::atomic<int> g_repairLeft{8};

/** 当前线程正在 guard 内部处理，防止修复逻辑把 JNI 调用绕回自己。 */
thread_local bool t_busy = false;

#if defined(__aarch64__)
#define CUR_LR()                                     \
    ({                                               \
        void *_l;                                    \
        __asm__ __volatile__("mov %0, x30" : "=r"(_l)); \
        _l;                                          \
    })
#else
#define CUR_LR() __builtin_return_address(0)
#endif

/**
 * 合法字符串指针判定。
 *
 * 下界 0x10000000 而不是 0x10000：光遇实测的 0xfffff9 正好落在两者之间，
 * 这就是为什么 1.6.12 装了防护还是崩 —— 它被当成「正常指针」直接进了 ART。
 */
inline bool badPtr(const void *p) {
    uintptr_t v = (uintptr_t) p;
    return v < 0x10000000ULL || v > 0x7fffffffffffULL;
}

/** 该页是否真的映射过。mincore 对未映射地址返回 ENOMEM，比直接读安全。 */
bool mapped(const void *p) {
    if (badPtr(p)) return false;
    unsigned char vec = 0;
    uintptr_t page = (uintptr_t) p & ~(uintptr_t) 0xfff;
    return mincore((void *) page, 0x1000, &vec) == 0;
}

/**
 * 尽量安全地把 p 当 C 字符串拷出来：非映射页不读，不可打印字符换成 '.'。
 * 只用于写日志，宁可显示不全也不能在诊断代码里再崩一次。
 */
const char *safeStr(const void *p, char *buf, size_t n) {
    if (n == 0) return buf;
    if (p == nullptr) {
        snprintf(buf, n, "(null)");
        return buf;
    }
    if (badPtr(p)) {
        snprintf(buf, n, "<%p>", p);
        return buf;
    }
    if (!mapped(p)) {
        snprintf(buf, n, "<%p:unmapped>", p);
        return buf;
    }
    const char *s = (const char *) p;
    size_t i = 0;
    for (; i + 1 < n; ++i) {
        if ((i & 0xfff) == 0 && !mapped(s + i)) break;
        unsigned char c = (unsigned char) s[i];
        if (c == 0) break;
        buf[i] = (c >= 0x20 && c < 0x7f) ? (char) c : '.';
    }
    buf[i] = '\0';
    return buf;
}

/** 把调用者地址翻成「哪个 so + 偏移」，比一个裸 lr 有用得多。 */
const char *modOf(void *addr, char *buf, size_t n) {
    Dl_info info;
    if (dladdr(addr, &info) != 0 && info.dli_fname != nullptr) {
        uintptr_t off = (uintptr_t) addr - (uintptr_t) info.dli_fbase;
        snprintf(buf, n, "%s+0x%lx", info.dli_fname, (unsigned long) off);
    } else {
        snprintf(buf, n, "?");
    }
    return buf;
}

// ---------------------------------------------------------------------------
// 调用历史环：崩溃前最后 8 次 GetMethodID 的参数。
// ---------------------------------------------------------------------------

struct HistEntry {
    void *lr;
    void *cls;
    char name[32];
    char sig[64];
    int bad;
};

const int kHist = 8;
HistEntry g_hist[kHist];
std::atomic<int> g_histSeq{0};

void pushHist(void *lr, void *cls, const void *name, const void *sig, int bad) {
    int i = g_histSeq.fetch_add(1, std::memory_order_relaxed) & (kHist - 1);
    g_hist[i].lr = lr;
    g_hist[i].cls = cls;
    g_hist[i].bad = bad;
    safeStr(name, g_hist[i].name, sizeof(g_hist[i].name));
    safeStr(sig, g_hist[i].sig, sizeof(g_hist[i].sig));
}

// ---------------------------------------------------------------------------
// 签名级别的修复：name 坏了、sig 还在时，按 sig 在类里找唯一匹配的方法。
// ---------------------------------------------------------------------------

/** 把一个 Class 对象翻成 JVM 描述符；失败返回空串。 */
std::string typeDesc(JNIEnv *env, jobject cls) {
    if (cls == nullptr) return std::string();

    jclass classCls = env->FindClass("java/lang/Class");
    if (classCls == nullptr) {
        env->ExceptionClear();
        return std::string();
    }
    jmethodID isArray = env->GetMethodID(classCls, "isArray", "()Z");
    jmethodID getName = env->GetMethodID(classCls, "getName", "()Ljava/lang/String;");
    jmethodID getComp = env->GetMethodID(classCls, "getComponentType", "()Ljava/lang/Class;");
    if (isArray == nullptr || getName == nullptr || getComp == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(classCls);
        return std::string();
    }

    std::string out;
    if (env->CallBooleanMethod(cls, isArray) == JNI_TRUE) {
        jobject comp = env->CallObjectMethod(cls, getComp);
        out = "[";
        out += typeDesc(env, comp);
        if (comp != nullptr) env->DeleteLocalRef(comp);
    } else {
        jstring js = (jstring) env->CallObjectMethod(cls, getName);
        const char *nm = (js == nullptr) ? nullptr : env->GetStringUTFChars(js, nullptr);
        if (nm != nullptr) {
            if (strcmp(nm, "boolean") == 0) out = "Z";
            else if (strcmp(nm, "byte") == 0) out = "B";
            else if (strcmp(nm, "char") == 0) out = "C";
            else if (strcmp(nm, "short") == 0) out = "S";
            else if (strcmp(nm, "int") == 0) out = "I";
            else if (strcmp(nm, "long") == 0) out = "J";
            else if (strcmp(nm, "float") == 0) out = "F";
            else if (strcmp(nm, "double") == 0) out = "D";
            else if (strcmp(nm, "void") == 0) out = "V";
            else {
                out = "L";
                for (const char *p = nm; *p != '\0'; ++p) out += (*p == '.') ? '/' : *p;
                out += ";";
            }
            env->ReleaseStringUTFChars(js, nm);
        }
        if (js != nullptr) env->DeleteLocalRef(js);
    }
    env->DeleteLocalRef(classCls);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return out;
}

/**
 * 在 cls 里找「签名恰好等于 sig」的方法。唯一命中才返回名字，多个命中返回空串。
 * 只看本类声明的方法（getDeclaredMethods），不做继承展开 —— 宁可漏，不要错。
 */
std::string uniqueNameBySig(JNIEnv *env, jclass cls, const char *sig) {
    if (cls == nullptr || sig == nullptr) return std::string();

    jclass classCls = env->FindClass("java/lang/Class");
    jclass methodCls = env->FindClass("java/lang/reflect/Method");
    if (classCls == nullptr || methodCls == nullptr) {
        env->ExceptionClear();
        if (classCls != nullptr) env->DeleteLocalRef(classCls);
        if (methodCls != nullptr) env->DeleteLocalRef(methodCls);
        return std::string();
    }
    jmethodID getDecl = env->GetMethodID(classCls, "getDeclaredMethods", "()[Ljava/lang/reflect/Method;");
    jmethodID getName = env->GetMethodID(methodCls, "getName", "()Ljava/lang/String;");
    jmethodID getRet = env->GetMethodID(methodCls, "getReturnType", "()Ljava/lang/Class;");
    jmethodID getPars = env->GetMethodID(methodCls, "getParameterTypes", "()[Ljava/lang/Class;");
    if (getDecl == nullptr || getName == nullptr || getRet == nullptr || getPars == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(classCls);
        env->DeleteLocalRef(methodCls);
        return std::string();
    }

    std::string hit;
    int hits = 0;
    jobjectArray arr = (jobjectArray) env->CallObjectMethod(cls, getDecl);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        arr = nullptr;
    }
    jsize n = (arr == nullptr) ? 0 : env->GetArrayLength(arr);
    for (jsize i = 0; i < n; ++i) {
        jobject m = env->GetObjectArrayElement(arr, i);
        if (m == nullptr) continue;

        std::string desc = "(";
        jobjectArray pars = (jobjectArray) env->CallObjectMethod(m, getPars);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            pars = nullptr;
        }
        jsize pc = (pars == nullptr) ? 0 : env->GetArrayLength(pars);
        for (jsize k = 0; k < pc; ++k) {
            jobject p = env->GetObjectArrayElement(pars, k);
            desc += typeDesc(env, p);
            if (p != nullptr) env->DeleteLocalRef(p);
        }
        if (pars != nullptr) env->DeleteLocalRef(pars);
        desc += ")";
        jobject ret = env->CallObjectMethod(m, getRet);
        desc += typeDesc(env, ret);
        if (ret != nullptr) env->DeleteLocalRef(ret);

        if (desc == sig) {
            ++hits;
            if (hits == 1) {
                jstring js = (jstring) env->CallObjectMethod(m, getName);
                const char *nm = (js == nullptr) ? nullptr : env->GetStringUTFChars(js, nullptr);
                if (nm != nullptr) {
                    hit.assign(nm);
                    env->ReleaseStringUTFChars(js, nm);
                }
                if (js != nullptr) env->DeleteLocalRef(js);
            }
        }
        env->DeleteLocalRef(m);
        if (hits > 1) break;
    }
    if (arr != nullptr) env->DeleteLocalRef(arr);

    env->DeleteLocalRef(classCls);
    env->DeleteLocalRef(methodCls);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return (hits == 1) ? hit : std::string();
}

/** 尽力拿到 clazz 的类名，纯粹为了日志。 */
const char *classNameOf(JNIEnv *env, jclass c, char *buf, size_t n) {
    buf[0] = '\0';
    if (c == nullptr) {
        snprintf(buf, n, "(null)");
        return buf;
    }
    jclass classCls = env->FindClass("java/lang/Class");
    if (classCls == nullptr) {
        env->ExceptionClear();
        return buf;
    }
    jmethodID getName = env->GetMethodID(classCls, "getName", "()Ljava/lang/String;");
    if (getName != nullptr) {
        jstring js = (jstring) env->CallObjectMethod(c, getName);
        if (js != nullptr) {
            const char *nm = env->GetStringUTFChars(js, nullptr);
            if (nm != nullptr) {
                snprintf(buf, n, "%s", nm);
                env->ReleaseStringUTFChars(js, nm);
            }
            env->DeleteLocalRef(js);
        }
    }
    env->DeleteLocalRef(classCls);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return buf;
}

// ---------------------------------------------------------------------------
// 三个 detour。这是「走表」和「直调原始地址」共同的入口。
// ---------------------------------------------------------------------------

jmethodID handleBadMethod(JNIEnv *env, jclass c, const char *name, const char *sig, void *lr,
                          const char *api) {
    char nb[64], sb[96], cb[160], mod[256];
    safeStr(name, nb, sizeof(nb));
    safeStr(sig, sb, sizeof(sb));
    classNameOf(env, c, cb, sizeof(cb));
    modOf(lr, mod, sizeof(mod));

    if (g_badLog.fetch_sub(1) > 0) {
        DiagSink::write("JNI-HIT %s BAD name=%p \"%s\" sig=%p \"%s\" cls=%s(%p) env=%p "
                        "tbl=%p tblGM=%p sym=%p dobby=%d lr=%p at=%s",
                        api, (const void *) name, nb, (const void *) sig, sb, cb, (void *) c,
                        (void *) env, (void *) g_tbl,
                        (void *) (g_tbl ? g_tbl->GetMethodID : nullptr), g_addrGM,
                        g_dobbyGM ? 1 : 0, lr, mod);
    }

    if (!badPtr(sig) && mapped(sig) && sig[0] == '(' && g_repairLeft.fetch_sub(1) > 0) {
        std::string nm = uniqueNameBySig(env, c, sig);
        if (!nm.empty() && o_gm != nullptr) {
            jmethodID r = o_gm(env, c, nm.c_str(), sig);
            if (r != nullptr) {
                DiagSink::write("JNI-REPAIR %s sig=%s -> %s (%p) at=%s", api, sig, nm.c_str(),
                                (void *) r, mod);
                return r;
            }
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    return nullptr;
}

/**
 * jclass == nullptr 时的兜底类。
 *
 * 光遇的壳在 installContentProviders 之前会拿 ActivityThread.mProviderMap
 * 调 values()。容器那个替身类里 mProviderMap 是 null，壳又不检查
 * GetObjectClass 的异常，于是带着 null class 进 GetMethodID。
 *
 * 这里的映射是**签名唯一**的，不是猜名字：
 *   values()Ljava/util/Collection; 只有 java.util.Map 有
 *   hasNext()Z / next()Ljava/lang/Object; 只有 java.util.Iterator 有
 *   toString()Ljava/lang/String; 任何类都有，用 Object（Declared 查找会落到
 *   Object 自己的实现上，语义与 null 检查前一致）
 */
const char *fallbackClassOf(const char *name, const char *sig) {
    if (name == nullptr || sig == nullptr) return nullptr;
    if (strcmp(name, "values") == 0 && strcmp(sig, "()Ljava/util/Collection;") == 0)
        return "java/util/Map";
    if (strcmp(name, "keySet") == 0 && strcmp(sig, "()Ljava/util/Set;") == 0)
        return "java/util/Map";
    if (strcmp(name, "entrySet") == 0 && strcmp(sig, "()Ljava/util/Set;") == 0)
        return "java/util/Map";
    if (strcmp(name, "hasNext") == 0 && strcmp(sig, "()Z") == 0) return "java/util/Iterator";
    if (strcmp(name, "next") == 0 && strcmp(sig, "()Ljava/lang/Object;") == 0)
        return "java/util/Iterator";
    if (strcmp(name, "toString") == 0 && strcmp(sig, "()Ljava/lang/String;") == 0)
        return "java/lang/Object";
    return nullptr;
}

jmethodID handleNullClass(JNIEnv *env, const char *name, const char *sig, void *lr) {
    char nb[64], sb[96], mod[256];
    const char *clsName = fallbackClassOf(name, sig);
    DiagSink::write("JNI-NULLCLS name=%s sig=%s fallback=%s at=%s",
                    safeStr(name, nb, sizeof(nb)), safeStr(sig, sb, sizeof(sb)),
                    clsName ? clsName : "(none)", modOf(lr, mod, sizeof(mod)));

    if (clsName == nullptr || o_gm == nullptr) return nullptr;

    t_busy = true;
    jclass k = env->FindClass(clsName);
    if (env->ExceptionCheck()) env->ExceptionClear();
    jmethodID r = (k != nullptr) ? o_gm(env, k, name, sig) : nullptr;
    if (k != nullptr) env->DeleteLocalRef(k);
    t_busy = false;
    return r;
}

jmethodID JNICALL det_GetMethodID(JNIEnv *env, jclass c, const char *name, const char *sig) {
    void *lr = CUR_LR();
    // jclass == null 同样会要命，而且原因不同（见下）：ART 在 null class 上
    // 走不到「抛 NPE 返回 null」的常规分支，会拿一个内部 sentinel 当字符串
    // 去 strlen，直接 SIGSEGV。1.6.17 的崩溃（x0=0xfffff9，即 -7）就是它。
    if (c == nullptr && !badPtr(name) && !badPtr(sig)) {
        pushHist(lr, c, name, sig, 2);
        return handleNullClass(env, name, sig, lr);
    }
    if (t_busy || (!badPtr(name) && !badPtr(sig))) {
        int seq = g_histSeq.load(std::memory_order_relaxed);
        pushHist(lr, c, name, sig, 0);
        if (!t_busy && g_okLog.fetch_sub(1) > 0) {
            char nb[64], sb[112], mod[256];
            DiagSink::write("JNI-CALL #%d name=%s sig=%s cls=%p at=%s", seq,
                            safeStr(name, nb, sizeof(nb)), safeStr(sig, sb, sizeof(sb)),
                            (void *) c, modOf(lr, mod, sizeof(mod)));
        }
        return o_gm ? o_gm(env, c, name, sig) : nullptr;
    }
    pushHist(lr, c, name, sig, 1);
    t_busy = true;
    jmethodID r = handleBadMethod(env, c, name, sig, lr, "GetMethodID");
    t_busy = false;
    return r;
}

jmethodID JNICALL det_GetStaticMethodID(JNIEnv *env, jclass c, const char *name, const char *sig) {
    void *lr = CUR_LR();
    if (c == nullptr && !badPtr(name) && !badPtr(sig)) {
        char nb[64], sb[96], mod[256];
        DiagSink::write("JNI-NULLCLS(static) name=%s sig=%s at=%s", safeStr(name, nb, sizeof(nb)),
                        safeStr(sig, sb, sizeof(sb)), modOf(lr, mod, sizeof(mod)));
        return nullptr;
    }
    if (t_busy || (!badPtr(name) && !badPtr(sig))) {
        if (!t_busy && g_okLog.fetch_sub(1) > 0) {
            char nb[64], sb[96], mod[256];
            DiagSink::write("JNI-LIVE GetStaticMethodID name=%s sig=%s at=%s",
                            safeStr(name, nb, sizeof(nb)), safeStr(sig, sb, sizeof(sb)),
                            modOf(lr, mod, sizeof(mod)));
        }
        return o_gsm ? o_gsm(env, c, name, sig) : nullptr;
    }
    if (!g_dobbyGSM && o_gsm == nullptr) return nullptr;
    t_busy = true;
    jmethodID r = handleBadMethod(env, c, name, sig, lr, "GetStaticMethodID");
    t_busy = false;
    return r;
}

jfieldID JNICALL det_GetFieldID(JNIEnv *env, jclass c, const char *name, const char *sig) {
    void *lr = CUR_LR();
    if (t_busy || (!badPtr(name) && !badPtr(sig))) {
        return o_gf ? o_gf(env, c, name, sig) : nullptr;
    }
    if (g_badLog.fetch_sub(1) > 0) {
        char nb[64], sb[96], mod[256];
        DiagSink::write("JNI-HIT GetFieldID BAD name=%p \"%s\" sig=%p \"%s\" lr=%p at=%s",
                        (const void *) name, safeStr(name, nb, sizeof(nb)), (const void *) sig,
                        safeStr(sig, sb, sizeof(sb)), lr, modOf(lr, mod, sizeof(mod)));
    }
    return nullptr;
}

}  // namespace

namespace JniEnvGuard {

std::string install(JNIEnv *env) {
    if (g_installed) return "already";
    if (env == nullptr) return "env null";

    auto *tbl = const_cast<JNINativeInterface *>(env->functions);
    if (tbl == nullptr) return "functions null";
    g_tbl = tbl;

    g_addrGM = (void *) tbl->GetMethodID;
    g_addrGSM = (void *) tbl->GetStaticMethodID;
    g_addrGF = (void *) tbl->GetFieldID;

    // -----------------------------------------------------------------------
    // 2.6.9：**不再对 libart 做 inline hook，只换函数表。**
    //
    // 2.6.8 的日志把死因钉死了：
    //   JNI-LIVE GetStaticMethodID name=isProxyClass ...
    //   JNI-LIVE GetStaticMethodID name=isDebuggerConnected ...
    //   ABORT-MESSAGE stack corruption detected (-fstack-protector)
    //   EXIT abort() caller=libc.so+...__stack_chk_fail
    // 壳先查「是不是动态代理」、再查「有没有调试器」，然后主动 abort。
    // 它能在 20ms 内做出判断，靠的是校验 ART 导出函数的机器码 ——
    // DobbyHook 会在 GetMethodID 入口写下一条 b 跳板，前 16 字节和出厂
    // 镜像对不上，壳一比对就知道被动了手脚。
    //
    // 所以这里退回最保守的形态：表项照换（壳走 env->functions 仍然会被
    // 纠正坏指针），但 libart 的代码段一个字节都不改。壳直调原始地址时
    // 拿到的是未经处理的 ID —— 对合法参数本来就该原样返回，所以没有损失。
    // -----------------------------------------------------------------------
    o_gm = (GetMethodId_t) g_addrGM;
    o_gsm = (GetMethodId_t) g_addrGSM;
    o_gf = (GetFieldId_t) g_addrGF;
    int r1 = -2, r2 = -2, r3 = -2;  // -2 = 未尝试 inline hook

    // -----------------------------------------------------------------------
    // 第二步：表项也换掉。两条路都进 detour，转发统一走 trampoline。
    // -----------------------------------------------------------------------
    long pageSz = sysconf(_SC_PAGESIZE);
    if (pageSz <= 0) pageSz = 4096;
    uintptr_t page = ((uintptr_t) tbl) & ~(uintptr_t) (pageSz - 1);
    size_t len = (size_t) pageSz * 2;

    bool tblOk = false;
    if (mprotect((void *) page, len, PROT_READ | PROT_WRITE) == 0) {
        tbl->GetMethodID = det_GetMethodID;
        tbl->GetStaticMethodID = det_GetStaticMethodID;
        tbl->GetFieldID = det_GetFieldID;
        mprotect((void *) page, len, PROT_READ);
        __builtin___clear_cache((char *) page, (char *) (page + len));
        tblOk = true;
    }

    g_installed = true;

    char buf[320];
    snprintf(buf, sizeof(buf),
             "ok tbl=%p(hooked=%d) sym=%p dobby=%d/%d/%d r=%d/%d/%d low=0x10000000",
             (void *) tbl, tblOk ? 1 : 0, g_addrGM, g_dobbyGM ? 1 : 0, g_dobbyGSM ? 1 : 0,
             g_dobbyGF ? 1 : 0, r1, r2, r3);
    DiagSink::write("JNI-GUARD %s", buf);
    return buf;
}

/**
 * 崩溃时把状态和历史打出来。只读静态内存 + DiagSink，不做 JNI 调用，
 * 可以在信号处理器里安全执行。
 */
void dumpState() {
    DiagSink::write("JNI-STATE tbl=%p slotGM=%p sym=%p dobby=%d histSeq=%d", (void *) g_tbl,
                    (void *) (g_tbl ? g_tbl->GetMethodID : nullptr), g_addrGM, g_dobbyGM ? 1 : 0,
                    g_histSeq.load(std::memory_order_relaxed));
    int seq = g_histSeq.load(std::memory_order_relaxed);
    for (int k = 0; k < kHist; ++k) {
        int i = (seq + k) & (kHist - 1);
        if (g_hist[i].lr == nullptr) continue;
        char mod[192];
        modOf(g_hist[i].lr, mod, sizeof(mod));
        DiagSink::write("JNI-HIST#%d bad=%d name=%s sig=%s cls=%p at=%s", k, g_hist[i].bad,
                        g_hist[i].name, g_hist[i].sig, g_hist[i].cls, mod);
    }
}

}  // namespace JniEnvGuard

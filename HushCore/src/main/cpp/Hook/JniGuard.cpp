//
// JniGuard.cpp
//
// 崩溃链已经查明：libunisec.so → libart(JNI 实现) → libc 字符串函数 → SEGV。
// 也就是说易盾把「本就该是字符串指针」的位置填了一个垃圾值（0xffffe0 这种），
// ART 拿到后直接 strlen，进程当场消失。
//
// 这里做两件事：
//   1) 兜底：把 JNIEnv 表里所有「入参是字符串」的接口包一层，指针不可读就换成
//      空串/空值并记录调用者。这类崩溃原本 100% 致死，换成空串至少能继续跑，
//      同时把「谁传了什么」留在日志里。
//   2) 反查：把 libart 里的地址翻译成 "JNI#167 NewStringUTF"，让后续崩溃的
//      调用栈直接可读。
//

#include "JniGuard.h"

#include <cerrno>
#include <cstddef>
#include <cstdio>
#include <cstring>

#include <sys/mman.h>
#include <unistd.h>

#include "DiagSink.h"
#include "ExitTrace.h"

namespace {

const int kMaxJniIdx = 233;   // 4 个保留位 + 229 个接口

void *g_jniTable[kMaxJniIdx];
bool g_installed = false;

// 每个被包住的接口的原始实现
typedef jstring (*newUtf_t)(JNIEnv *, const char *);
typedef jstring (*newStr_t)(JNIEnv *, const jchar *, jsize);
typedef jclass (*findClass_t)(JNIEnv *, const char *);
typedef jmethodID (*getMethod_t)(JNIEnv *, jclass, const char *, const char *);
typedef jfieldID (*getField_t)(JNIEnv *, jclass, const char *, const char *);
typedef jint (*throwNew_t)(JNIEnv *, jclass, const char *);

newUtf_t g_newUtf = nullptr;
newStr_t g_newStr = nullptr;
findClass_t g_findClass = nullptr;
getMethod_t g_getMethodID = nullptr;
getMethod_t g_getStaticMethodID = nullptr;
getField_t g_getFieldID = nullptr;
getField_t g_getStaticFieldID = nullptr;
throwNew_t g_throwNew = nullptr;

/**
 * 快速闸门：先做两次比较，绝大多数调用连函数都不进。
 *
 * arm64 上真正的用户态指针都在 16MB 以上（代码、so、ART 堆、栈全在高地址），
 * 而这次的坏指针是 0xfffff9 / 0xffffe0（16MB 减几十字节）。
 * 上一版把闸门设成 0x10000（64KB），所以 0xffffe0 被判成"正常指针"直接放行，
 * 一路走到 libart 的 strlen 里才炸 —— 日志里那两行 bad ptr 全是误报，
 * 真正的凶手反而漏了。
 *
 * 现在：< 16MB 或 落在内核半区 → 进深检查；其余一律原样放行，零开销。
 */
inline bool suspect(const void *p) {
    uintptr_t v = (uintptr_t) p;
    if (v >= (uintptr_t) 0x01000000 && v < (uintptr_t) 0x0000800000000000ULL)
        return false;
    return v != 0;
}

/** 可疑指针：现查一次映射（快照 + mincore），读不了才算坏指针。 */
inline bool reallyBad(const void *p, size_t n) {
    if (p == nullptr) return false;
    if (!suspect(p)) return false;
    return !ExitTrace::safeToRead(p, n);
}

/** 前若干次 NewStringUTF 原样记录，用来确认易盾到底往表里传了什么。 */
int g_callLogs = 24;

/** 记录次数上限，避免热门接口刷爆日志。 */
int g_badLogs = 0;

void logBad(const char *api, const void *p, const void *ret) {
    if (g_badLogs >= 24) return;
    g_badLogs++;
    char who[192];
    ExitTrace::describe(ret, who, sizeof(who));
    DiagSink::write("JNI %s bad ptr=%p caller=%s", api, p, who);
}

inline jstring wrapNewStringUTF(JNIEnv *env, const char *chars) {
    if (g_callLogs > 0) {
        g_callLogs--;
        char who[192];
        ExitTrace::describe(__builtin_return_address(0), who, sizeof(who));
        DiagSink::write("JNI-CALL NewStringUTF ptr=%p caller=%s", (const void *) chars, who);
    }
    if (chars != nullptr && reallyBad(chars, 1)) {
        logBad("NewStringUTF", chars, __builtin_return_address(0));
        return g_newUtf(env, "");
    }
    return g_newUtf(env, chars);
}

inline jstring wrapNewString(JNIEnv *env, const jchar *u, jsize len) {
    if (len > 0 && reallyBad(u, (size_t) len * sizeof(jchar))) {
        logBad("NewString", u, __builtin_return_address(0));
        return g_newStr(env, nullptr, 0);
    }
    return g_newStr(env, u, len);
}

inline jclass wrapFindClass(JNIEnv *env, const char *name) {
    if (name != nullptr && reallyBad(name, 1)) {
        logBad("FindClass", name, __builtin_return_address(0));
        return nullptr;
    }
    return g_findClass(env, name);
}

inline jmethodID GetMethodIDImpl(JNIEnv *env, jclass c, const char *name, const char *sig,
                                 getMethod_t orig, const char *api) {
    if ((name != nullptr && reallyBad(name, 1)) || (sig != nullptr && reallyBad(sig, 1))) {
        logBad(api, (name != nullptr && reallyBad(name, 1)) ? (const void *) name
                                                           : (const void *) sig,
               __builtin_return_address(0));
        return nullptr;
    }
    return orig(env, c, name, sig);
}

inline jfieldID GetFieldIDImpl(JNIEnv *env, jclass c, const char *name, const char *sig,
                               getField_t orig, const char *api) {
    if ((name != nullptr && reallyBad(name, 1)) || (sig != nullptr && reallyBad(sig, 1))) {
        logBad(api, (name != nullptr && reallyBad(name, 1)) ? (const void *) name
                                                           : (const void *) sig,
               __builtin_return_address(0));
        return nullptr;
    }
    return orig(env, c, name, sig);
}

inline jint wrapThrowNew(JNIEnv *env, jclass c, const char *msg) {
    if (msg != nullptr && reallyBad(msg, 1)) {
        logBad("ThrowNew", msg, __builtin_return_address(0));
        return g_throwNew(env, c, "");
    }
    return g_throwNew(env, c, msg);
}

const char *jniName(int i) {
    switch (i) {
        case 4: return "GetVersion";
        case 5: return "DefineClass";
        case 6: return "FindClass";
        case 7: return "FromReflectedMethod";
        case 9: return "ToReflectedMethod";
        case 10: return "GetSuperclass";
        case 11: return "IsAssignableFrom";
        case 13: return "Throw";
        case 14: return "ThrowNew";
        case 15: return "ExceptionOccurred";
        case 16: return "ExceptionDescribe";
        case 17: return "ExceptionClear";
        case 21: return "NewGlobalRef";
        case 22: return "DeleteGlobalRef";
        case 23: return "DeleteLocalRef";
        case 25: return "NewLocalRef";
        case 27: return "AllocObject";
        case 28: return "NewObject";
        case 31: return "GetObjectClass";
        case 32: return "IsInstanceOf";
        case 33: return "GetMethodID";
        case 34: return "CallObjectMethod";
        case 49: return "CallIntMethod";
        case 61: return "CallVoidMethod";
        case 94: return "GetFieldID";
        case 95: return "GetObjectField";
        case 100: return "GetIntField";
        case 113: return "GetStaticMethodID";
        case 114: return "CallStaticObjectMethod";
        case 129: return "CallStaticIntMethod";
        case 141: return "CallStaticVoidMethod";
        case 144: return "GetStaticFieldID";
        case 145: return "GetStaticObjectField";
        case 150: return "GetStaticIntField";
        case 163: return "NewString";
        case 164: return "GetStringLength";
        case 165: return "GetStringChars";
        case 166: return "ReleaseStringChars";
        case 167: return "NewStringUTF";
        case 168: return "GetStringUTFLength";
        case 169: return "GetStringUTFChars";
        case 170: return "ReleaseStringUTFChars";
        case 171: return "GetArrayLength";
        case 172: return "NewObjectArray";
        case 173: return "GetObjectArrayElement";
        case 174: return "SetObjectArrayElement";
        case 176: return "NewByteArray";
        case 179: return "NewIntArray";
        case 183: return "GetBooleanArrayElements";
        case 184: return "GetByteArrayElements";
        case 187: return "GetIntArrayElements";
        case 192: return "ReleaseByteArrayElements";
        case 195: return "ReleaseIntArrayElements";
        case 200: return "GetByteArrayRegion";
        case 203: return "GetIntArrayRegion";
        case 208: return "SetByteArrayRegion";
        case 211: return "SetIntArrayRegion";
        case 215: return "RegisterNatives";
        case 216: return "UnregisterNatives";
        case 217: return "MonitorEnter";
        case 218: return "MonitorExit";
        case 219: return "GetJavaVM";
        case 220: return "GetStringRegion";
        case 221: return "GetStringUTFRegion";
        case 222: return "GetPrimitiveArrayCritical";
        case 223: return "ReleasePrimitiveArrayCritical";
        case 224: return "GetStringCritical";
        case 225: return "ReleaseStringCritical";
        case 228: return "ExceptionCheck";
        case 229: return "NewDirectByteBuffer";
        case 232: return "GetObjectRefType";
        default: return nullptr;
    }
}

}  // namespace

namespace JniGuard {

void install(JNIEnv *env) {
    if (g_installed || env == nullptr) return;

    const JNINativeInterface *tbl = *reinterpret_cast<const JNINativeInterface *const *>(env);
    if (tbl == nullptr) return;

    // 1) 先把整张表快照下来：崩溃时靠它把 libart 的地址翻译成 JNI 接口名
    const void *const *raw = reinterpret_cast<const void *const *>(tbl);
    for (int i = 0; i < kMaxJniIdx; i++) g_jniTable[i] = const_cast<void *>(raw[i]);

    // 2) 整表一次解锁（表跨页时逐项 mprotect 会算错边界）
    JNINativeInterface *mt = const_cast<JNINativeInterface *>(tbl);
    const long ps = sysconf(_SC_PAGESIZE);
    const uintptr_t lo = ((uintptr_t) tbl) & ~((uintptr_t) ps - 1);
    const uintptr_t hi = (((uintptr_t) tbl + sizeof(void *) * kMaxJniIdx) + (uintptr_t) ps - 1)
                         & ~((uintptr_t) ps - 1);
    if (mprotect((void *) lo, (size_t) (hi - lo), PROT_READ | PROT_WRITE) != 0) {
        DiagSink::write("JNI-GUARD mprotect failed errno=%d table=%p", errno, (void *) tbl);
        g_installed = true;
        return;
    }

    int patched = 0;
    if (mt->NewStringUTF != nullptr) {
        g_newUtf = mt->NewStringUTF;
        mt->NewStringUTF = wrapNewStringUTF;
        patched++;
    }
    if (mt->NewString != nullptr) {
        g_newStr = mt->NewString;
        mt->NewString = wrapNewString;
        patched++;
    }
    if (mt->FindClass != nullptr) {
        g_findClass = mt->FindClass;
        mt->FindClass = wrapFindClass;
        patched++;
    }
    if (mt->GetMethodID != nullptr) {
        g_getMethodID = mt->GetMethodID;
        mt->GetMethodID = [](JNIEnv *e, jclass c, const char *n, const char *s) -> jmethodID {
            return GetMethodIDImpl(e, c, n, s, g_getMethodID, "GetMethodID");
        };
        patched++;
    }
    if (mt->GetStaticMethodID != nullptr) {
        g_getStaticMethodID = mt->GetStaticMethodID;
        mt->GetStaticMethodID = [](JNIEnv *e, jclass c, const char *n, const char *s) -> jmethodID {
            return GetMethodIDImpl(e, c, n, s, g_getStaticMethodID, "GetStaticMethodID");
        };
        patched++;
    }
    if (mt->GetFieldID != nullptr) {
        g_getFieldID = mt->GetFieldID;
        mt->GetFieldID = [](JNIEnv *e, jclass c, const char *n, const char *s) -> jfieldID {
            return GetFieldIDImpl(e, c, n, s, g_getFieldID, "GetFieldID");
        };
        patched++;
    }
    if (mt->GetStaticFieldID != nullptr) {
        g_getStaticFieldID = mt->GetStaticFieldID;
        mt->GetStaticFieldID = [](JNIEnv *e, jclass c, const char *n, const char *s) -> jfieldID {
            return GetFieldIDImpl(e, c, n, s, g_getStaticFieldID, "GetStaticFieldID");
        };
        patched++;
    }
    if (mt->ThrowNew != nullptr) {
        g_throwNew = mt->ThrowNew;
        mt->ThrowNew = wrapThrowNew;
        patched++;
    }

    mprotect((void *) lo, (size_t) (hi - lo), PROT_READ);
    g_installed = true;

    // 顺带把两个索引算出来写日志：jni.h 的结构体顺序就是规范顺序，
    // 用 offsetof 推导比手写 167 更不容易错。
    DiagSink::write("JNI-GUARD patched=%d NewString=#%d NewStringUTF=#%d table=%p",
                    patched,
                    (int) (offsetof(JNINativeInterface, NewString) / sizeof(void *)),
                    (int) (offsetof(JNINativeInterface, NewStringUTF) / sizeof(void *)),
                    (void *) tbl);
}

const char *nameOf(const void *addr) {
    static char out[48];
    if (addr == nullptr) return nullptr;
    for (int i = 0; i < kMaxJniIdx; i++) {
        if (g_jniTable[i] == addr) {
            const char *nm = jniName(i);
            if (nm != nullptr)
                snprintf(out, sizeof(out), "JNI#%d %s", i, nm);
            else
                snprintf(out, sizeof(out), "JNI#%d", i);
            return out;
        }
    }
    return nullptr;
}

}  // namespace JniGuard

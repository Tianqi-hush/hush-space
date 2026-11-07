#include "ArtJniIdHook.h"

#include <android/log.h>
#include <cstdint>
#include <cstdio>
#include <cstring>

#include "Dobby/dobby.h"
#include "SandHook/xdl.h"

#define LOG_TAG "HushCore"

namespace {

constexpr const char *kArtPath = "/apex/com.android.art/lib64/libart.so";

// ---------------------------------------------------------------------------
// 一、为什么以前那版「装了等于没装」
//
// 1.6.8 的探针打出 mid=0x265c828（0x100000000 以下，明显不是指针），说明这台
// 机器的 ART 开着 -Xopaque-jni-ids，jmethodID 是「索引」不是 ArtMethod*。
//
// 上一版的替换函数写成了 uint64_t Stub(void* handle)，以为 x0 就是 ArtMethod*。
// 实际签名是（从拾光 libhexin.so 0x57b30 的替换函数反汇编确认）：
//
//     uintptr_t JniIdManager::EncodeGenericId<T>(JniIdManager* self,  // x0
//                                                ReflectiveHandle<T>* h); // x1
//
// 也就是说 x0 是 this（JniIdManager*），真正的 handle 在 x1 指向的内存里。
// 上一版直接 return x0，等于把 JniIdManager 自己的地址当成 jmethodID 交出去，
// 壳一解引用就崩 —— 1.6.8 光遇的崩溃点从「libunisec 直接读」变成
// 「libart 内部 strlen(0xfffff9)」，就是返回值被换成了错的东西造成的。
//
// 二、这一版的做法
//
// 编解码两侧必须同时改，否则一边返回指针、另一边还当索引查表，必然错位。
// 所以四个符号一起 hook，并且**只有在一次真实往返里确认 Encode 和 Decode 都
// 真的被调用到**（= 没有被编译器内联掉）才打开直通，任何一个环节没命中就退回
// 原始语义，保证不会把进程搞崩。
// ---------------------------------------------------------------------------

void *g_handle = nullptr;

void *g_encM = nullptr, *g_decM = nullptr, *g_encF = nullptr, *g_decF = nullptr;
void *o_encM = nullptr, *o_decM = nullptr, *o_encF = nullptr, *o_decF = nullptr;

uint64_t n_encM = 0, n_decM = 0, n_encF = 0, n_decF = 0;

/** method id 走直通（返回 ArtMethod*）。 */
bool g_passM = false;
/** field id 走直通（返回 ArtField*）。 */
bool g_passF = false;
bool g_installed = false;

// ---------------------------------------------------------------------------
// 替换函数。ABI 必须和原函数一致，参数个数一个都不能少。
// ---------------------------------------------------------------------------

/** uintptr_t EncodeGenericId<ArtMethod>(JniIdManager* self, ReflectiveHandle<ArtMethod>* h) */
uintptr_t HookEncM(void *self, void **h) {
    __atomic_add_fetch(&n_encM, 1, __ATOMIC_RELAXED);
    if (g_passM && h != nullptr) return (uintptr_t) (*h);
    return ((uintptr_t(*)(void *, void **)) o_encM)(self, h);
}

/** ArtMethod* DecodeGenericId<ArtMethod>(const JniIdManager* self, uintptr_t id) */
void *HookDecM(void *self, uintptr_t id) {
    __atomic_add_fetch(&n_decM, 1, __ATOMIC_RELAXED);
    if (g_passM) return (void *) id;
    return ((void *(*)(void *, uintptr_t)) o_decM)(self, id);
}

/** uintptr_t EncodeGenericId<ArtField>(JniIdManager* self, ReflectiveHandle<ArtField>* h) */
uintptr_t HookEncF(void *self, void **h) {
    __atomic_add_fetch(&n_encF, 1, __ATOMIC_RELAXED);
    if (g_passF && h != nullptr) return (uintptr_t) (*h);
    return ((uintptr_t(*)(void *, void **)) o_encF)(self, h);
}

/** ArtField* DecodeGenericId<ArtField>(const JniIdManager* self, uintptr_t id) */
void *HookDecF(void *self, uintptr_t id) {
    __atomic_add_fetch(&n_decF, 1, __ATOMIC_RELAXED);
    if (g_passF) return (void *) id;
    return ((void *(*)(void *, uintptr_t)) o_decF)(self, id);
}

/**
 * 按「名字包含 substr」找导出符号。
 *
 * 不写死 mangled 长度：EncodeGenericId 和 DecodeGenericId 都是 15 个字符，
 * 上一版探针里手写成了 14，于是 decM/decF 永远是 0，白等了一版日志。
 */
/**
 * 精确符号名优先，substring 兜底。
 *
 * 1.6.11 的日志暴露了一个事实：同一时刻 BoxCore 的探针用 xdl_dsym 精确名
 * 找到了 encM（打出 encM=1），而这里用 xdl_list_syms 的 substring 搜索
 * 四个全部 miss —— 说明这个 ROM 上 xdl_list_syms 走不通（探针里的
 * syms(0)= 也是空的）。符号查找必须走精确名。
 */
void *findExact(void *handle, const char *a, const char *b) {
    void *p = (a != nullptr) ? xdl_dsym(handle, a, nullptr) : nullptr;
    if (p == nullptr && b != nullptr) p = xdl_dsym(handle, b, nullptr);
    if (p == nullptr && b != nullptr) p = xdl_sym(handle, b, nullptr);
    return p;
}

void *findContains(void *handle, const char *substr, char *picked, size_t pickedSz) {
    char buf[2048];
    int n = xdl_list_syms(handle, substr, buf, sizeof(buf), 6);
    if (n <= 0) return nullptr;
    if (picked != nullptr && pickedSz > 0) picked[0] = '\0';

    char *save = nullptr;
    for (char *tok = strtok_r(buf, " ", &save); tok != nullptr;
         tok = strtok_r(nullptr, " ", &save)) {
        void *addr = xdl_dsym(handle, tok, nullptr);
        if (addr == nullptr) addr = xdl_sym(handle, tok, nullptr);
        if (addr != nullptr) {
            if (picked != nullptr && pickedSz > 0) {
                snprintf(picked, pickedSz, "%s", tok);
            }
            return addr;
        }
    }
    return nullptr;
}

/**
 * 一次真实 JNI 往返，顺便把四个 hook 的命中数差出来。
 *
 * 走两个不同的方法（实例 + 静态）和一个字段，避免被 ART 的 method-id 缓存
 * 挡住而误判成「没命中」。
 */
struct Hits {
    uint64_t e, d;
};

std::string roundTrip(JNIEnv *env, Hits *methodHits, Hits *fieldHits, int *midIsPtr) {
    uint64_t e0 = n_encM, d0 = n_decM, ef0 = n_encF, df0 = n_decF;

    char note[160];
    note[0] = '\0';
    int midPtr = -1;

    jclass strCls = env->FindClass("java/lang/String");
    if (strCls != nullptr) {
        jmethodID mid = env->GetMethodID(strCls, "length", "()I");
        if (mid != nullptr) {
            midPtr = ((uintptr_t) mid > 0x100000000ULL) ? 1 : 0;
            jstring s = env->NewStringUTF("mundo");
            if (s != nullptr) {
                env->CallIntMethod(s, mid);
                env->DeleteLocalRef(s);
            }
        }
        env->DeleteLocalRef(strCls);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    jclass uuidCls = env->FindClass("java/util/UUID");
    if (uuidCls != nullptr) {
        jmethodID sid = env->GetStaticMethodID(uuidCls, "randomUUID", "()Ljava/util/UUID;");
        if (sid != nullptr) env->CallStaticObjectMethod(uuidCls, sid);
        env->DeleteLocalRef(uuidCls);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    // 字段侧：Integer.value 是 int 实例字段，取一次就能逼出 Encode/Decode FieldId。
    jclass intCls = env->FindClass("java/lang/Integer");
    if (intCls != nullptr) {
        jfieldID fid = env->GetFieldID(intCls, "value", "I");
        (void) fid;
        env->DeleteLocalRef(intCls);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    if (methodHits != nullptr) {
        methodHits->e = n_encM - e0;
        methodHits->d = n_decM - d0;
    }
    if (fieldHits != nullptr) {
        fieldHits->e = n_encF - ef0;
        fieldHits->d = n_decF - df0;
    }
    if (midIsPtr != nullptr) *midIsPtr = midPtr;
    return std::string(note);
}

}  // namespace

namespace ArtJniIdHook {

std::string install(JNIEnv *env) {
    if (g_installed) return "already installed";

    if (g_handle == nullptr) g_handle = xdl_open(kArtPath, XDL_DEFAULT);
    if (g_handle == nullptr) return "open libart failed";

    char nm[4][320];
    // EncodeGenericId<ArtMethod> 在这台 ROM 上确实导出（探针 encM=1）。
    g_encM = findExact(g_handle,
                       "_ZN3art3jni12JniIdManager15EncodeGenericIdINS_9ArtMethodEEEmNS_16ReflectiveHandleIT_EE",
                       nullptr);
    if (g_encM == nullptr) {
        g_encM = findContains(g_handle, "15EncodeGenericIdINS_9ArtMethod", nm[0], sizeof(nm[0]));
    }
    // Decode 未导出（探针 decM=0），两种 const 修饰都试一遍，仍然拿不到就为空。
    g_decM = findExact(g_handle,
                       "_ZNK3art3jni12JniIdManager15DecodeGenericIdINS_9ArtMethodEEEPT_m",
                       "_ZN3art3jni12JniIdManager15DecodeGenericIdINS_9ArtMethodEEEPT_m");
    if (g_decM == nullptr) {
        g_decM = findContains(g_handle, "15DecodeGenericIdINS_9ArtMethod", nm[1], sizeof(nm[1]));
    }
    g_encF = findExact(g_handle,
                       "_ZN3art3jni12JniIdManager15EncodeGenericIdINS_9ArtFieldEEEmNS_16ReflectiveHandleIT_EE",
                       nullptr);
    if (g_encF == nullptr) {
        g_encF = findContains(g_handle, "15EncodeGenericIdINS_9ArtField", nm[2], sizeof(nm[2]));
    }
    g_decF = findExact(g_handle,
                       "_ZNK3art3jni12JniIdManager15DecodeGenericIdINS_9ArtFieldEEEPT_m",
                       "_ZN3art3jni12JniIdManager15DecodeGenericIdINS_9ArtFieldEEEPT_m");
    if (g_decF == nullptr) {
        g_decF = findContains(g_handle, "15DecodeGenericIdINS_9ArtField", nm[3], sizeof(nm[3]));
    }

    char head[512];
    snprintf(head, sizeof(head),
             "encM=%s decM=%s encF=%s decF=%s",
             g_encM ? "ok" : "miss", g_decM ? "ok" : "miss",
             g_encF ? "ok" : "miss", g_decF ? "ok" : "miss");
    std::string out(head);

    // 只要有一侧缺失，就不能单边改语义，直接放弃、保持 ART 原样。
    if (g_encM == nullptr || g_decM == nullptr) {
        out += " -> abandon(method pair missing)";
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "EncodeGenericId hook: %s", out.c_str());
        return out;
    }

    int r1 = DobbyHook(g_encM, (void *) HookEncM, &o_encM);
    int r2 = DobbyHook(g_decM, (void *) HookDecM, &o_decM);
    if (r1 != 0 || r2 != 0) {
        char buf[96];
        snprintf(buf, sizeof(buf), " -> DobbyHook failed r1=%d r2=%d", r1, r2);
        out += buf;
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "EncodeGenericId hook: %s", out.c_str());
        return out;
    }

    bool fieldReady = false;
    if (g_encF != nullptr && g_decF != nullptr) {
        int r3 = DobbyHook(g_encF, (void *) HookEncF, &o_encF);
        int r4 = DobbyHook(g_decF, (void *) HookDecF, &o_decF);
        fieldReady = (r3 == 0 && r4 == 0);
    }

    g_installed = true;

    // -----------------------------------------------------------------------
    // 关键一步：先透传跑一轮，看 hook 到底有没有被调用。
    // 模板函数会被编译器内联，内联之后即使 DobbyHook 返回 0，也永远不会命中。
    // 这种情况必须退回原始语义，否则就是「改了一半」比不改更糟。
    // -----------------------------------------------------------------------
    Hits mh{}, fh{};
    int midPtr = -1;
    roundTrip(env, &mh, &fh, &midPtr);

    char cnt[256];
    snprintf(cnt, sizeof(cnt),
             " | probe encM=%llu decM=%llu encF=%llu decF=%llu midIsPtr=%d",
             (unsigned long long) mh.e, (unsigned long long) mh.d,
             (unsigned long long) fh.e, (unsigned long long) fh.d, midPtr);
    out += cnt;

    if (mh.e > 0 && mh.d > 0) {
        g_passM = true;
        out += " modeM=pass";
    } else {
        out += " modeM=native";
    }
    if (fieldReady && fh.e > 0 && fh.d > 0) {
        g_passF = true;
        out += " modeF=pass";
    } else {
        out += " modeF=native";
    }

    // 打开直通后再跑一轮：这次 mid 应该变成高地址（真 ArtMethod*），
    // CallIntMethod 仍然要能正常返回，才说明编解码两侧确实一致。
    if (g_passM) {
        Hits mh2{}, fh2{};
        int midPtr2 = -1;
        roundTrip(env, &mh2, &fh2, &midPtr2);
        char v[192];
        snprintf(v, sizeof(v), " | verify encM=%llu decM=%llu midIsPtr=%d",
                 (unsigned long long) mh2.e, (unsigned long long) mh2.d, midPtr2);
        out += v;
        if (midPtr2 != 1) {
            // 没变成指针说明 hook 虽然命中但没作用到 GetMethodID 的真实路径上，
            // 留着直通只会让两条路径打架，退回。
            g_passM = false;
            out += " -> revertM";
        }
    }

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "EncodeGenericId hook: %s", out.c_str());
    return out;
}

}  // namespace ArtJniIdHook

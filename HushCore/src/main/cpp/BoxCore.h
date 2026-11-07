//
// Created by Milk on 4/9/21.
//

#ifndef VIRTUAL_APP_BOXCORE
#define VIRTUAL_APP_BOXCORE

#include <jni.h>
#include <unistd.h>
//#include <fb/include/fb/ALog.h>
//#include <fb/include/fb/fbjni.h>
#define VMCORE_CLASS "com/hush/core/VNative"

class BoxCore {
public:
    static JavaVM *getJavaVM();
    static int getApiLevel();
    static int getCallingUid(JNIEnv *env, int orig);
    static jstring redirectPathString(JNIEnv *env, jstring path);
    static jobject redirectPathFile(JNIEnv *env, jobject path);
    static jlongArray loadEmptyDex(JNIEnv *env);

    /** 取当前线程的 JNIEnv，必要时 attach。供 libc hook 侧调用（可能跨线程）。 */
    static JNIEnv *getEnv();

    /**
     * 让 Java 侧在 guest 的沙盒 lib 目录里找 so，返回绝对路径或 null。
     * 供 RuntimeHook 在 nativeLoad 失败时兜底。
     */
    static jstring resolveLibPath(JNIEnv *env, const char *soName);
};

#endif //VIRTUAL_APP_BOXCORE
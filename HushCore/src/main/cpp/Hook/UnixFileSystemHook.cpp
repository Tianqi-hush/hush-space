//
// Fixed UnixFileSystemHook.cpp
// Solves .pak write Permission denied (errno=13)
// Android 10–17 BlackBox compatible
//

#include <IO.h>
#include "UnixFileSystemHook.h"
#import "JniHook/JniHook.h"
#include "BaseHook.h"

/*
 * Class:     java_io_UnixFileSystem
 * Method:    canonicalize0
 * Signature: (Ljava/lang/String;)Ljava/lang/String;
 */
HOOK_JNI(jstring, canonicalize0, JNIEnv *env, jobject obj, jstring path) {
    jstring redirect = IO::redirectPath(env, path);
    jstring res = orig_canonicalize0(env, obj, redirect);
    env->DeleteLocalRef(redirect);
    return res;
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    getBooleanAttributes0
 * Signature: (Ljava/lang/String;)I
 */
HOOK_JNI(jint, getBooleanAttributes0, JNIEnv *env, jobject obj, jstring abspath) {
    jstring redirect = IO::redirectPath(env, abspath);
    jint res = orig_getBooleanAttributes0(env, obj, redirect);
    env->DeleteLocalRef(redirect);
    return res;
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    getLastModifiedTime0
 * Signature: (Ljava/io/File;)J
 */
HOOK_JNI(jlong, getLastModifiedTime0, JNIEnv *env, jobject obj, jobject path) {
    jobject redirect = IO::redirectPath(env, path);
    jlong res = orig_getLastModifiedTime0(env, obj, redirect);
    env->DeleteLocalRef(redirect);
    return res;
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    setPermission0
 * Signature: (Ljava/io/File;IZZ)Z
 */
HOOK_JNI(jboolean, setPermission0, JNIEnv *env, jobject obj, jobject file, jint access,
         jboolean enable, jboolean owneronly) {
    jobject redirect = IO::redirectPath(env, file);
    jboolean res = orig_setPermission0(env, obj, redirect, access, enable, owneronly);
    env->DeleteLocalRef(redirect);
    return res;
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    createFileExclusively0
 * Signature: (Ljava/lang/String;)Z
 */
HOOK_JNI(jboolean, createFileExclusively0, JNIEnv *env, jobject obj, jstring path) {
    jstring redirect = IO::redirectPath(env, path);
    jboolean res = orig_createFileExclusively0(env, obj, redirect);
    env->DeleteLocalRef(redirect);
    return res;
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    list0
 * Signature: (Ljava/io/File;)[Ljava/lang/String;
 */
HOOK_JNI(jobjectArray, list0, JNIEnv *env, jobject obj, jobject file) {
    jobject redirect = IO::redirectPath(env, file);
    jobjectArray res = orig_list0(env, obj, redirect);
    env->DeleteLocalRef(redirect);
    return res;
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    createDirectory0
 * Signature: (Ljava/io/File;)Z
 */
HOOK_JNI(jboolean, createDirectory0, JNIEnv *env, jobject obj, jobject path) {
    jobject redirect = IO::redirectPath(env, path);
    jboolean res = orig_createDirectory0(env, obj, redirect);
    env->DeleteLocalRef(redirect);
    return res;
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    setLastModifiedTime0
 * Signature: (Ljava/io/File;J)Z
 */
HOOK_JNI(jboolean, setLastModifiedTime0, JNIEnv *env, jobject obj, jobject file, jlong time) {
    jobject redirect = IO::redirectPath(env, file);
    jboolean res = orig_setLastModifiedTime0(env, obj, redirect, time);
    env->DeleteLocalRef(redirect);
    return res;
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    setReadOnly0
 * Signature: (Ljava/io/File;)Z
 */
HOOK_JNI(jboolean, setReadOnly0, JNIEnv *env, jobject obj, jobject file) {
    jobject redirect = IO::redirectPath(env, file);
    jboolean res = orig_setReadOnly0(env, obj, redirect);
    env->DeleteLocalRef(redirect);
    return res;
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    getSpace0
 * Signature: (Ljava/io/File;I)J
 */
HOOK_JNI(jlong, getSpace0, JNIEnv *env, jobject obj, jobject file, jint t) {
    jobject redirect = IO::redirectPath(env, file);
    jlong res = orig_getSpace0(env, obj, redirect, t);
    env->DeleteLocalRef(redirect);
    return res;
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    checkAccess0
 * Signature: (Ljava/io/File;I)Z
 */
HOOK_JNI(jboolean, checkAccess0, JNIEnv *env, jobject obj, jobject file, jint access) {
    jobject redirect = IO::redirectPath(env, file);
    jboolean res = orig_checkAccess0(env, obj, redirect, access);
    env->DeleteLocalRef(redirect);
    return res;
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    delete0
 * Signature: (Ljava/io/File;)Z
 */
HOOK_JNI(jboolean, delete0, JNIEnv *env, jobject obj, jobject file) {
    jobject redirect = IO::redirectPath(env, file);
    jboolean res = orig_delete0(env, obj, redirect);
    env->DeleteLocalRef(redirect);
    return res;
}

/*
 * Class:     java_io_UnixFileSystem
 * Method:    rename0
 * Signature: (Ljava/io/File;Ljava/io/File;)Z
 */
HOOK_JNI(jboolean, rename0, JNIEnv *env, jobject obj, jobject from, jobject to) {
    jobject redirectFrom = IO::redirectPath(env, from);
    jobject redirectTo = IO::redirectPath(env, to);
    jboolean res = orig_rename0(env, obj, redirectFrom, redirectTo);
    env->DeleteLocalRef(redirectFrom);
    env->DeleteLocalRef(redirectTo);
    return res;
}

void UnixFileSystemHook::init(JNIEnv *env) {
    const char *className = "java/io/UnixFileSystem";
    JniHook::HookJniFun(env, className, "canonicalize0", "(Ljava/lang/String;)Ljava/lang/String;",(void *) new_canonicalize0, (void **) (&orig_canonicalize0), false);
    JniHook::HookJniFun(env, className, "getBooleanAttributes0", "(Ljava/lang/String;)I",(void *) new_getBooleanAttributes0,(void **) (&orig_getBooleanAttributes0), false);
    JniHook::HookJniFun(env, className, "getLastModifiedTime0", "(Ljava/io/File;)J",(void *) new_getLastModifiedTime0, (void **) (&orig_getLastModifiedTime0),false);
    JniHook::HookJniFun(env, className, "setPermission0", "(Ljava/io/File;IZZ)Z",(void *) new_setPermission0, (void **) (&orig_setPermission0), false);
    JniHook::HookJniFun(env, className, "createFileExclusively0", "(Ljava/lang/String;)Z",(void *) new_createFileExclusively0,(void **) (&orig_createFileExclusively0), false);
    JniHook::HookJniFun(env, className, "list0", "(Ljava/io/File;)[Ljava/lang/String;",(void *) new_list0, (void **) (&orig_list0), false);
    JniHook::HookJniFun(env, className, "createDirectory0", "(Ljava/io/File;)Z",(void *) new_createDirectory0, (void **) (&orig_createDirectory0), false);
    JniHook::HookJniFun(env, className, "setLastModifiedTime0", "(Ljava/io/File;J)Z",(void *) new_setLastModifiedTime0, (void **) (&orig_setLastModifiedTime0),false);
    JniHook::HookJniFun(env, className, "setReadOnly0", "(Ljava/io/File;)Z",(void *) new_setReadOnly0, (void **) (&orig_setReadOnly0), false);
    JniHook::HookJniFun(env, className, "getSpace0", "(Ljava/io/File;I)J",(void *) new_getSpace0, (void **) (&orig_getSpace0), false);
    JniHook::HookJniFun(env, className, "checkAccess0", "(Ljava/io/File;I)Z",(void *) new_checkAccess0, (void **) (&orig_checkAccess0), false);
    JniHook::HookJniFun(env, className, "delete0", "(Ljava/io/File;)Z",(void *) new_delete0, (void **) (&orig_delete0), false);
    JniHook::HookJniFun(env, className, "rename0", "(Ljava/io/File;Ljava/io/File;)Z",(void *) new_rename0, (void **) (&orig_rename0), false);
}
//
// JniEnvGuard.h
//
// JNI 函数表 + 原始函数地址的双层前置校验。见 .cpp 里为什么必须两层。
//

#ifndef HUSHCORE_JNI_ENV_GUARD_H
#define HUSHCORE_JNI_ENV_GUARD_H

#include <jni.h>
#include <string>

namespace JniEnvGuard {

/** 安装一次，幂等。返回人类可读的结果，直接打进 diag.log。 */
std::string install(JNIEnv *env);

/** 只读静态状态，用于崩溃时由信号处理器调用。不碰 JNI，可重入安全。 */
void dumpState();

}  // namespace JniEnvGuard

#endif  // HUSHCORE_JNI_ENV_GUARD_H

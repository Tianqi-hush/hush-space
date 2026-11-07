//
// JniGuard.h
//
// 两个用途：
//   1) 反查：把 libart 里的地址翻译成 "JNI#167 NewStringUTF" —— ART 的符号
//      是隐藏的，但 JNIEnv 函数表里的每一项都直接指向实现，拿地址比一下就知道
//      崩溃那一帧到底在调哪个 JNI 接口。
//   2) 兜底：易盾在 onCreate 里拿一个坏指针去调 NewStringUTF 就会当场崩，
//      包一层可读性检查，坏指针换成空串，让它继续往下跑。
//

#ifndef HUSHCORE_JNI_GUARD_H
#define HUSHCORE_JNI_GUARD_H

#include <jni.h>
#include <cstddef>

namespace JniGuard {

/** 必须在 enableIO 里、崩溃可能发生之前调用。 */
void install(JNIEnv *env);

/** 地址若是 JNIEnv 函数表里的某一项，返回 "JNI#167 NewStringUTF"。 */
const char *nameOf(const void *addr);

}  // namespace JniGuard

#endif  // HUSHCORE_JNI_GUARD_H

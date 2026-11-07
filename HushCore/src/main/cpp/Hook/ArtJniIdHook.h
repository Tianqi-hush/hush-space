#pragma once

#include <jni.h>
#include <string>

/**
 * ART 「jmethodID 间接化」兼容层。
 *
 * Android 14 起，ART 的 JniIdManager 可以把 jmethodID 编码成一个索引（uint64），
 * 而不是直接给 ArtMethod*。JNI 规范从来没保证过它是指针，所以这本身合法；
 * 但大量加固壳在 native 里直接 `*(ArtMethod*)mid` 取字段，索引值被当成指针，
 * 读出来就是垃圾（我们看到的 strlen(0xfffff9) 正是这条链）。
 *
 * 拾光 5.3.91 的 libhexin.so 里对这件事的处理是：dlsym 下面这个符号，
 * inline hook 掉它，让编码函数原样返回 ArtMethod*。
 *
 *   _ZN3art3jni12JniIdManager15EncodeGenericIdINS_9ArtMethodEEEmNS_16ReflectiveHandleIT_EE
 *
 * 本文件按同样的方式实现，并且装完之后立刻做一次 JNI 往返自测：
 * GetMethodID + CallIntMethod。如果 ART 的解码端不接受指针，自测会当场崩，
 * 那就是「这条路走不通」的确定答案，而不是又一个黑盒。
 */
namespace ArtJniIdHook {

/** 装 hook 并自测。返回一行可直接写日志的结论。 */
std::string install(JNIEnv *env);

}  // namespace ArtJniIdHook

//
// NativeIOHook.h
//

#ifndef HUSHCORE_NATIVE_IO_HOOK_H
#define HUSHCORE_NATIVE_IO_HOOK_H

namespace NativeIO {

/** 挂上 libc 的 open/openat/fopen/stat 等符号，之后路径会按 IO 规则表改写。幂等。 */
void install();

/** 是否已成功挂上（open 系列至少一个）。 */
bool enabled();

/** 返回当前 hook 状态与指定路径的改写结果（静态缓冲，供 JNI 立即转成 jstring）。 */
const char *diag(const char *path);

}  // namespace NativeIO

#endif  // HUSHCORE_NATIVE_IO_HOOK_H

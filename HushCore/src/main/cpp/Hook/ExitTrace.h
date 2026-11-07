//
// ExitTrace.h
//

#ifndef HUSHCORE_EXIT_TRACE_H
#define HUSHCORE_EXIT_TRACE_H

namespace ExitTrace {

/**
 * 挂上 exit/_exit/_Exit/abort/kill/tgkill，把调用点写进 diagPath 指向的文件。
 * 必须在 NativeIO::install()（也就是 IO 重定向生效）之前调用，
 * 这样日志 fd 拿到的是宿主自己的真实路径。
 */
void install(const char *diagPath);

/** 判断 [p, p+n) 能不能安全读（拿 /proc/self/maps 的可读区间表比对）。 */
bool safeToRead(const void *p, size_t n);

/** 把地址写成 "libX.so+0x1234(~strlen+0x1c)" 的形式。 */
void describe(const void *addr, char *out, size_t cap);

/** 在正常上下文里主动打一份调用栈，行前缀用 tag（例如 "STR-BT"）。 */
void dumpStack(const char *tag);

}  // namespace ExitTrace

#endif  // HUSHCORE_EXIT_TRACE_H

//
// DiagSink.h
//

#ifndef HUSHCORE_DIAG_SINK_H
#define HUSHCORE_DIAG_SINK_H

#include <cstddef>

namespace DiagSink {

/** 在 IO 重定向生效之前调用一次；path 为 diag.log 的真实路径。 */
void open(const char *path);

/** 是否可用（fd 打开成功）。 */
bool ready();

int fd();

/** 追加一行，自动带时间戳/pid/tid/线程名。 */
void write(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

}  // namespace DiagSink

#endif  // HUSHCORE_DIAG_SINK_H

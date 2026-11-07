//
// StrGuard.h
//
// libc 字符串函数入口兜底。
//
// 崩溃现场已经稳定复现：
//     SIGNAL 11 addr=0xffffe0 x0=0xfffff9
//     libc(~strlen) ← libart+0x233984 ← libart+0x2b71c0 ← libart+0x688158
//     ← libunisec+0x7f264 ← libunisec+0x54900
//
// 即易盾算出了一个「16MB 减几十字节」的字符串指针交给 ART，ART 直接 strlen，
// 于是段错误、进程瞬间消失。
//
// 之前是在 JNIEnv 函数表入口拦，但这一条链路**不经过函数表**（日志里 22 次
// JNI-CALL 全是正常指针，且没有一条 bad ptr），所以拦不住。
// 现在把网下沉到 libc：只要传给字符串函数的指针根本不可读，就当成空串处理，
// 并把这个调用点连同完整调用栈写进日志。
//

#ifndef HUSHCORE_STR_GUARD_H
#define HUSHCORE_STR_GUARD_H

namespace StrGuard {

/** 必须在 enableIO 里、目标应用开始跑之前调用。 */
void install();

}  // namespace StrGuard

#endif  // HUSHCORE_STR_GUARD_H

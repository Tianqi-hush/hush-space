//
// ArtGuard.h
//

#ifndef HUSHCORE_ART_GUARD_H
#define HUSHCORE_ART_GUARD_H

namespace ArtGuard {

/**
 * 在 libart.so 里定位「把外部字符串交给 ART 建对象」的那个函数，并在入口处
 * 把明显不可能存在的字符串指针换成空串。
 *
 * 为什么必须在这么深的地方动手：崩溃栈已经证明
 *
 *     libunisec+0x54900 -> libunisec+0x7f264 -> libart+0x688158
 *                       -> libart+0x2b71c0 -> libart+0x233984(strlen)
 *
 * 易盾直接调 libart 内部函数（完全绕开 JNIEnv 函数表），传进来的却是一个
 * 0xfffff9 这样的"指针"。拦在 strlen 上只能挡住第一次 strlen，函数内部之后
 * 还会再用一次这个值，所以只能把参数在函数入口就换掉。
 *
 * 定位方式：用 .eh_frame_hdr 的检索表把「调用位置」反查成「函数起始地址」。
 * 若解析失败 / 校验不过，则只记录不挂钩 —— 宁可少一层网，不能挂错函数。
 */
void install();

}  // namespace ArtGuard

#endif  // HUSHCORE_ART_GUARD_H

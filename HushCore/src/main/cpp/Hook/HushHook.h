#pragma once

#include <jni.h>

/**
 * HushHook 的薄封装。
 *
 * 为什么需要它：容器里 guest 的 Application/系统服务都是我们自己在 Java 层
 * 伪造的，但加固壳（易盾）的校验走的是 ART 内部调用链 —— 它在 native 里
 * 直接 CallObjectMethod 拿 PackageManager，不经过我们的 proxy。要在这种
 * 链路上改变返回值，唯一的办法是在 ART 层把目标 Java 方法本身换掉。
 *
 * 这里用的就是一套 ART 层方法替换引擎。
 * 这里只负责三件事：初始化、hook、unhook。
 */
namespace HushHook {

    /** 保存 JavaVM，供后台线程 Attach 用。在 JNI_OnLoad 里调用。 */
    void setVm(JavaVM *vm);

    /**
     * 起一个后台线程去做 Init，调用方立即返回。
     *
     * 为什么不能在主线程直接 Init：HushHook 的 Init 内部会 ScopedSuspendAll，
     * 要求除自己之外的所有线程进 safepoint。而调用它的主线程正站在 native 里
     * 等它返回 —— native 代码不检查 safepoint，于是 Init 永远等不到，死锁。
     * 现场就是：日志停在 System.load → nativeLoad，之后连心跳都没了。
     *
     * 改成后台线程 Init 之后，主线程回到 Java 层（Thread.sleep 轮询），
     * 可以被正常挂起，Init 才能推进。
     */
    void startAsyncInit();

    /** 同步初始化（主线程空闲、确定不会死锁时使用）。成功返回 true。 */
    bool install(JNIEnv *env);

    bool isReady();

    /** Init 已经失败（不是"还没跑完"），调用方可以立刻放弃等待。 */
    bool hasFailed();

    /**
     * target   : 要被替换的 Java 方法（java.lang.reflect.Method）
     * hooker   : 承载上下文的普通对象，必须有一个 callback 方法
     * callback : hooker 上的 callback，签名必须是 Object callback(Object[])
     * 返回 backup 方法（调用它等价于调用原方法），失败返回 null。
     */
    jobject hook(JNIEnv *env, jobject target, jobject hooker, jobject callback);

    bool unhook(JNIEnv *env, jobject target);
}

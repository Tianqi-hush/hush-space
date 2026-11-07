package com.hush.core.hook;

import java.lang.reflect.Method;

/**
 * HushHook backup 的统一调用入口。
 *
 * 这里的规则不是猜的，是 1.6.5 的日志直接给出的：
 *
 * <pre>
 * sign: Int args=3(ApplicationPackageManager,String,Integer)
 *       backup=[class java.lang.String, int]
 *       ret=null err=java.lang.NullPointerException: null receiver
 * </pre>
 *
 * 三件事同时成立：
 *
 * <ol>
 *   <li>回调拿到的 args 有 3 个，第一个是 receiver（ApplicationPackageManager 实例）；</li>
 *   <li>backup 的形参表只有 2 个（String, int），不含 receiver；</li>
 *   <li>对 backup 执行 {@code invoke(null, ...)} 报 {@code null receiver}。</li>
 * </ol>
 *
 * 合起来只有一个解释：**backup 是非静态方法**，receiver 要从 args[0] 单独传，
 * 不能塞进参数数组里。之前的 {@code invoke(null, args)} 既少了一个 receiver，
 * 又多传了一个实参，ART 先检查 receiver，于是直接 NPE。
 *
 * 静态方法（Process.killProcess、Runtime.load0）的形参表与 args 等长，
 * 走另一条分支，行为与原来完全一致。
 */
public final class HookInvoke {

    private HookInvoke() {
    }

    /**
     * 调用 backup。
     *
     * @param backup HushHook 返回的 backup Method
     * @param args   回调收到的参数表（实例方法时 args[0] 是 receiver）
     */
    public static Object call(Method backup, Object[] args) throws Throwable {
        if (backup == null) {
            throw new IllegalStateException("backup is null");
        }
        Class<?>[] pt = backup.getParameterTypes();
        int n = args == null ? 0 : args.length;

        // 形参表与实参等长：静态方法，原样透传。
        if (pt.length == n) {
            return backup.invoke(null, args);
        }

        // 形参表比实参少一个：实例方法，args[0] 就是 receiver。
        if (pt.length == n - 1 && n > 0) {
            Object[] rest = new Object[n - 1];
            System.arraycopy(args, 1, rest, 0, n - 1);
            return backup.invoke(args[0], rest);
        }

        // 数量对不上，说明约定又变了。按静态调用，让异常照原样抛出去，
        // 由调用方记日志——绝不悄悄返回 null。
        return backup.invoke(null, args);
    }
}

package com.hush.core.xposed;

import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.hush.core.VNative;
import com.hush.utils.Diag;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Xposed 桥的 Java 分派层。
 *
 * <p>数据流：
 * <pre>
 *   模块 XposedHelpers.findAndHookMethod(...)
 *        └─> XposedBridge.hookMethod(member, callback)
 *              └─> 这里：一个目标方法对应一个 Hooker
 *                    └─> VNative.hookMethod(target, hooker, dispatch)
 *                          └─> HushHook 生成同签名桩，调用转到 Hooker.dispatch(Object[])
 * </pre>
 *
 * <p>HushHook 的回调约定正好是「{@code Object dispatch(Object[] args)}」：
 * 实例方法 {@code args[0]} 是 this，静态方法没有占位。所以不需要字节码生成，
 * 一个通用分派器就能接住任意签名的方法。
 */
public final class XposedEngine {

    private static final String TAG = "xp";

    /** 目标方法 -> Hooker。一个目标只挂一次 HushHook，多个模块的回调排队执行。 */
    private static final Map<Member, Hooker> sHooks = new ConcurrentHashMap<>();

    private static Method sDispatch;

    private XposedEngine() {
    }

    public static void log(String msg) {
        Diag.log(TAG, msg);
    }

    // ── 安装 / 卸载 ────────────────────────────────────────────────────

    public static XC_MethodHook.Unhook hook(Member member, XC_MethodHook callback) {
        if (member == null || callback == null) {
            return null;
        }
        if (!(member instanceof Method) && !(member instanceof Constructor)) {
            log("skip non-executable hook: " + member);
            return null;
        }
        Executable target = (Executable) member;
        if (member instanceof Method && Modifier.isAbstract(target.getModifiers())) {
            log("skip abstract: " + target);
            return null;
        }

        synchronized (sHooks) {
            Hooker hooker = sHooks.get(member);
            if (hooker == null) {
                if (!ensureHookReady()) {
                    log("HushHook 未就绪，放弃 hook " + target);
                    return null;
                }
                hooker = new Hooker(target);
                Method backup;
                try {
                    backup = VNative.hookMethod(target, hooker, dispatchMethod());
                } catch (Throwable t) {
                    log("VNative.hookMethod 抛异常 " + target + " " + t);
                    return null;
                }
                if (backup == null) {
                    log("hook 失败（HushHook 返回 null）: " + target);
                    return null;
                }
                hooker.backup = backup;
                sHooks.put(member, hooker);
                log("hook " + target.getDeclaringClass().getName() + "#" + target.getName()
                        + "(" + target.getParameterTypes().length + " 参)");
            }
            if (!hooker.callbacks.contains(callback)) {
                hooker.callbacks.add(callback);
                hooker.sort();
            }
            return callback.new Unhook(member);
        }
    }

    public static void unhook(Member member, XC_MethodHook callback) {
        if (member == null || callback == null) {
            return;
        }
        synchronized (sHooks) {
            Hooker hooker = sHooks.get(member);
            if (hooker == null) {
                return;
            }
            hooker.callbacks.remove(callback);
            if (hooker.callbacks.isEmpty()) {
                if (member instanceof Method) {
                    try {
                        VNative.unhookMethod((Method) member);
                    } catch (Throwable ignored) {
                    }
                }
                sHooks.remove(member);
            }
        }
    }

    public static boolean isHooked(Member member) {
        return member != null && sHooks.containsKey(member);
    }

    public static int hookCount() {
        return sHooks.size();
    }

    /**
     * 补触发：模块装上 hook 时，目标方法已经执行完了。
     *
     * <p>最典型的是 {@code Application#attach(Context)}。它是 final 小方法，
     * ART 在 boot image 里会把它**内联**进 {@code Instrumentation.newApplication}，
     * HushHook 把方法入口改成跳板也拦不到那次调用 —— 日志里能看到
     * 「hook android.app.Application#attach(1 参)」，却永远等不到「触发」。
     *
     * <p>只运行模块回调，**不再执行原方法**（原方法容器已经执行过）。
     * 只补 hits==0 的 hooker，所以不会重复执行模块自己的逻辑。
     */
    public static int replayMissed(Class<?> declaringClass, String methodName,
                                   Object thisObject, Object[] args, String why) {
        int replayed = 0;
        for (Map.Entry<Member, Hooker> entry : sHooks.entrySet()) {
            Hooker hooker = entry.getValue();
            if (!(hooker.target instanceof Method)) {
                continue;
            }
            Method method = (Method) hooker.target;
            if (!method.getName().equals(methodName)) {
                continue;
            }
            if (declaringClass != null && method.getDeclaringClass() != declaringClass) {
                continue;
            }
            if (hooker.hits.get() != 0) {
                continue;
            }
            try {
                hooker.replay(thisObject, args);
                replayed++;
                log("补触发 " + method.getDeclaringClass().getName() + "#" + method.getName()
                        + " 回调=" + hooker.callbacks.size() + " 原因=" + why);
            } catch (Throwable t) {
                log("补触发失败 " + method.getDeclaringClass().getName() + "#" + method.getName()
                        + " " + t);
            }
        }
        return replayed;
    }

    /**
     * 加载完模块后起一个后台线程，几秒后把「装了但从没触发」的 hook 打出来。
     *
     * <p>没有这行日志时，「注入了、hook 挂上了、功能全无」只能靠猜；
     * 有了它，一眼就能看出是方法没被调用（内联/时机）还是模块内部失败。
     */
    public static void reportNeverTriggered(final long delayMs) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    int missed = 0;
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<Member, Hooker> entry : sHooks.entrySet()) {
                        Hooker hooker = entry.getValue();
                        if (hooker.hits.get() != 0) {
                            continue;
                        }
                        missed++;
                        if (missed <= 8) {
                            if (missed > 1) {
                                sb.append(", ");
                            }
                            sb.append(hooker.describe());
                        }
                    }
                    if (missed > 0) {
                        log("从未触发的 hook=" + missed + "/" + sHooks.size()
                                + " [" + sb + (missed > 8 ? ", ..." : "") + "]");
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "hush-xp-watch");
        thread.setDaemon(true);
        thread.start();
    }

    public static Object invokeOriginal(Member member, Object thisObject, Object[] args) throws Throwable {
        Hooker hooker = sHooks.get(member);
        Method backup = hooker == null ? null : hooker.backup;
        if (backup == null) {
            if (!(member instanceof Method)) {
                throw new IllegalArgumentException("constructor backup 不可用: " + member);
            }
            backup = (Method) member;
        }
        backup.setAccessible(true);
        try {
            return backup.invoke(Modifier.isStatic(backup.getModifiers()) ? null : thisObject,
                    args == null ? EMPTY : args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // ── HushHook 就绪 ───────────────────────────────────────────────────

    /**
     * 等 HushHook 初始化完成。
     *
     * <p>Init 是在 enableIO 里由后台线程做的（很重：要读 libart 符号表、
     * suspend 所有线程）。这里只在主线程 sleep 轮询，最多 6 秒；失败就放弃，
     * 不影响 App 启动。
     */
    public static boolean ensureHookReady() {
        try {
            if (VNative.isHookReady()) {
                return true;
            }
            VNative.setHookGate(true);
            VNative.startHookInit();
            long deadline = SystemClock.uptimeMillis() + 6000L;
            while (!VNative.isHookReady() && !VNative.isHookFailed()
                    && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(20);
            }
            boolean ready = VNative.isHookReady();
            if (!ready) {
                log(VNative.isHookFailed() ? "HushHook 初始化失败（见失败阶段）"
                                           : "HushHook 6 秒内没就绪");
            }
            return ready;
        } catch (Throwable t) {
            log("ensureHookReady 失败 " + t);
            return false;
        }
    }

    private static synchronized Method dispatchMethod() throws NoSuchMethodException {
        if (sDispatch == null) {
            sDispatch = Hooker.class.getDeclaredMethod("dispatch", Object[].class);
            sDispatch.setAccessible(true);
        }
        return sDispatch;
    }

    private static final Object[] EMPTY = new Object[0];

    // ── 分派器 ─────────────────────────────────────────────────────────

    /** 一个目标方法一个实例，保存 backup 与回调队列。 */
    public static final class Hooker {

        private final Executable target;
        private final boolean isStatic;
        final List<XC_MethodHook> callbacks = new CopyOnWriteArrayList<>();
        volatile Method backup;
        /** 命中计数：只用来限流打日志，确认 hook 到底有没有被调用。 */
        private final java.util.concurrent.atomic.AtomicInteger hits =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger errors =
                new java.util.concurrent.atomic.AtomicInteger();

        Hooker(Executable target) {
            this.target = target;
            this.isStatic = target instanceof Method
                    && Modifier.isStatic(target.getModifiers());
        }

        void sort() {
            List<XC_MethodHook> sorted = new ArrayList<>(callbacks);
            Collections.sort(sorted, new Comparator<XC_MethodHook>() {
                @Override
                public int compare(XC_MethodHook a, XC_MethodHook b) {
                    return b.getPriority() - a.getPriority();
                }
            });
            callbacks.clear();
            callbacks.addAll(sorted);
        }

        /**
         * HushHook 生成桩调过来的入口。
         *
         * <p>{@code args} 里实例方法第一个元素是 this，静态方法没有占位 ——
         * 这点和 Xposed 的 {@code MethodHookParam.args} 不一样，这里把 this
         * 剥出去，保证模块拿到的 args 与真机一致。
         */
        public Object dispatch(Object[] args) throws Throwable {
            Object thisObject;
            Object[] realArgs;
            if (isStatic) {
                thisObject = null;
                realArgs = args == null ? EMPTY : args;
            } else {
                if (args == null || args.length == 0) {
                    thisObject = null;
                    realArgs = EMPTY;
                } else {
                    thisObject = args[0];
                    realArgs = new Object[args.length - 1];
                    System.arraycopy(args, 1, realArgs, 0, realArgs.length);
                }
            }

            XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
            param.method = target;
            param.thisObject = thisObject;
            param.args = realArgs;

            int hit = hits.incrementAndGet();
            if (hit == 1) {
                log("触发 " + target.getDeclaringClass().getName() + "#" + target.getName()
                        + " this=" + (thisObject == null ? "null"
                        : thisObject.getClass().getName()) + " 参数=" + realArgs.length);
            }

            List<XC_MethodHook> snapshot = new ArrayList<>(callbacks);

            for (XC_MethodHook callback : snapshot) {
                try {
                    callback.handleBefore(param);
                } catch (Throwable t) {
                    logHookError("before", callback, t);
                    // 不 setThrowable：模块回调自己抛的异常只说明这条 hook 没写成，
                    // 不代表原方法该失败。xeno 的 Sky$1 把 ContextWrapper.attachBaseContext
                    // 的 this 强转 Activity，抛的 ClassCastException 被当成原方法的异常
                    // 抛出去，直接 makeApplication 失败 → 进程 _exit(10)，guest 起不来。
                    // 需要「让原方法抛异常」的模块会用 param.setThrowable() 显式表达。
                }
                if (param.hasThrowable()) {
                    break;
                }
            }

            if (!param.hasThrowable() && !param.isResultSet()) {
                try {
                    // 用 param.args 而不是构造时的 realArgs：模块可以整个替换数组。
                    param.setResult(callOriginal(thisObject, param.args));
                } catch (Throwable t) {
                    param.setThrowable(t);
                }
            }

            for (int i = snapshot.size() - 1; i >= 0; i--) {
                try {
                    snapshot.get(i).handleAfter(param);
                } catch (Throwable t) {
                    logHookError("after", snapshot.get(i), t);
                    // 不 setThrowable：after 阶段原方法已经执行完，模块的异常没有
                    // 语义。传出去只会把 guest 的启动链一起带走 —— Star 的
                    // StubAppHook 里 findClass 失败就是这样让 attachBaseContext
                    // 抛异常、makeApplication 失败、进程 _exit(10) 的。
                }
            }

            return param.getResultOrThrowable();
        }

        /**
         * 补触发一次：只跑回调，不执行原方法。
         *
         * <p>模块的回调期望「原方法刚执行完」，所以 before 之后直接把结果置空，
         * 跳过 callOriginal，再按逆序跑 after。
         */
        void replay(Object thisObject, Object[] realArgs) {
            hits.incrementAndGet();
            XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
            param.method = target;
            param.thisObject = thisObject;
            param.args = realArgs == null ? EMPTY : realArgs.clone();

            List<XC_MethodHook> snapshot = new ArrayList<>(callbacks);
            for (XC_MethodHook callback : snapshot) {
                try {
                    callback.handleBefore(param);
                } catch (Throwable t) {
                    logHookError("before", callback, t);
                    // 不 setThrowable：模块回调自己抛的异常只说明这条 hook 没写成，
                    // 不代表原方法该失败。xeno 的 Sky$1 把 ContextWrapper.attachBaseContext
                    // 的 this 强转 Activity，抛的 ClassCastException 被当成原方法的异常
                    // 抛出去，直接 makeApplication 失败 → 进程 _exit(10)，guest 起不来。
                    // 需要「让原方法抛异常」的模块会用 param.setThrowable() 显式表达。
                }
                if (param.hasThrowable()) {
                    break;
                }
            }
            if (!param.hasThrowable() && !param.isResultSet()) {
                // 原方法容器已经执行过，这里只补回调，绝不能再调一次。
                param.setResult(null);
            }
            for (int i = snapshot.size() - 1; i >= 0; i--) {
                try {
                    snapshot.get(i).handleAfter(param);
                } catch (Throwable t) {
                    logHookError("after", snapshot.get(i), t);
                    // 不 setThrowable：after 阶段原方法已经执行完，模块的异常没有
                    // 语义。传出去只会把 guest 的启动链一起带走 —— Star 的
                    // StubAppHook 里 findClass 失败就是这样让 attachBaseContext
                    // 抛异常、makeApplication 失败、进程 _exit(10) 的。
                }
            }
            if (param.hasThrowable()) {
                log("补触发回调抛异常 " + describe() + " " + param.getThrowable());
            }
        }

        String describe() {
            return target.getDeclaringClass().getName() + "#" + target.getName();
        }

        /** 模块回调里抛异常时不能静默：否则表现就是「注入了但功能全无」。 */
        private void logHookError(String phase, XC_MethodHook callback, Throwable t) {
            int n = errors.incrementAndGet();
            if (n <= 5 || n % 200 == 0) {
                log("回调异常(" + phase + " #" + n + ") "
                        + target.getDeclaringClass().getName() + "#" + target.getName()
                        + " cb=" + callback.getClass().getName() + " " + t);
            }
        }

        private Object callOriginal(Object thisObject, Object[] args) throws Throwable {
            Method method = backup;
            if (method == null) {
                throw new IllegalStateException("backup 为空: " + target);
            }
            method.setAccessible(true);
            try {
                return method.invoke(isStatic ? null : thisObject, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    static {
        // 让 XposedBridge 的常量类在 guest 里一定会被加载，模块反射拿得到。
        try {
            Class.forName(XposedBridge.class.getName(), true, XposedBridge.class.getClassLoader());
        } catch (Throwable ignored) {
        }
    }
}

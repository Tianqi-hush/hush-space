package com.hush.core.xposed;

import android.util.Log;

import com.hush.utils.Diag;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 把 guest 里模块自己的日志 / so 加载过程镜像进 diag.log。
 *
 * <p>为什么需要：模块（Star / zll）几乎把所有异常都 catch 掉，只写自己的
 * 日志文件或 Logcat。容器侧看过去就是「注入了、hook 也挂了，但什么反应都
 * 没有」，完全没有可观测性。这里挂两个点：
 *
 * <ul>
 *   <li>{@code Log.println} —— 只放行 tag 里带 sky/star/zll/skymod 的行，
 *       以及 ERROR 以上级别；带去重，避免刷屏。</li>
 *   <li>{@code System.load / loadLibrary} —— 模块加载 native 库（zll 的
 *       libfeature.so）成功与否一目了然。</li>
 * </ul>
 *
 * <p>两个点都选的是普通 Java 方法，不是 native 方法：native 方法一旦 backup
 * 拿不到，原函数就调不起来了 —— 那是把「看不见」换成「直接崩」，不能赌。
 */
public final class GuestLog {

    private static boolean sInstalled;
    private static final ThreadLocal<Boolean> sIn = new ThreadLocal<Boolean>();
    private static final Set<String> sSeen = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    private GuestLog() {
    }

    public static synchronized void install() {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        hookPrintln();
        hookNativeLoad();
    }

    private static boolean interesting(String tag, int priority) {
        if (priority >= 6) {
            return true;
        }
        if (tag == null) {
            return false;
        }
        String t = tag.toLowerCase();
        return t.contains("sky") || t.contains("star") || t.contains("zll")
                || t.contains("skymod") || t.contains("gamehook");
    }

    private static void hookPrintln() {
        Method target = null;
        try {
            target = Log.class.getDeclaredMethod("println",
                    int.class, int.class, String.class, String.class);
            target.setAccessible(true);
        } catch (Throwable t) {
            Diag.log("xp", "Log.println 不可用，改用 println_native: " + t);
            try {
                target = Log.class.getDeclaredMethod("println_native",
                        int.class, int.class, String.class, String.class);
                target.setAccessible(true);
            } catch (Throwable ignored) {
            }
        }
        if (target == null) {
            return;
        }
        try {
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sIn.get() != null) {
                        return;
                    }
                    try {
                        int priority = (Integer) param.args[1];
                        String tag = (String) param.args[2];
                        String msg = (String) param.args[3];
                        if (msg == null || !interesting(tag, priority)) {
                            return;
                        }
                        String key = tag + "|" + (msg.length() > 96 ? msg.substring(0, 96) : msg);
                        if (sSeen.size() < 500 && !sSeen.add(key)) {
                            return;
                        }
                        sIn.set(Boolean.TRUE);
                        Diag.log("glog", tag + ": " + msg);
                    } catch (Throwable ignored) {
                    } finally {
                        sIn.remove();
                    }
                }
            });
            Diag.log("xp", "模块日志镜像已安装 " + target.getName());
        } catch (Throwable t) {
            Diag.log("xp", "模块日志镜像安装失败 " + t);
        }
    }

    /**
     * 只 hook {@code System.load(String)}，<b>绝不 hook {@code System.loadLibrary}</b>。
     *
     * <p>{@code System.loadLibrary} 是 @CallerSensitive：它内部靠
     * {@code Reflection.getCallerClass()} 决定用哪个 ClassLoader 的
     * nativeLibraryDirectories 去找 so。HushHook 把方法入口换成了宿主里的
     * LSPHooker 桩，桩上没有 @CallerSensitive 注解，getCallerClass 于是返回桩自身，
     * loader 变成宿主的 —— 加固壳的 libunisec.so / libandroidmainruns.so 全部
     * 「dlopen failed: library not found」，壳解不出 dex，业务类一个都不存在。
     *
     * <p>{@code System.load} 收的是绝对路径，不依赖 caller，可以安全 hook。
     */
    private static void hookNativeLoad() {
        hookLoad("load", String.class);
    }

    private static void hookLoad(String name, Class<?> arg) {
        try {
            Method m = System.class.getDeclaredMethod(name, arg);
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sIn.get() != null) {
                        return;
                    }
                    sIn.set(Boolean.TRUE);
                    try {
                        String path = String.valueOf(param.args[0]);
                        Diag.log("gload", "System." + name + " " + path);
                        // System.load 内部靠 Reflection.getCallerClass() 决定把 so 绑到
                        // 哪个 ClassLoader。HushHook 把入口换成了宿主里的 LSPHooker 桩，
                        // getCallerClass 返回的是桩（宿主 loader），so 于是绑错 loader：
                        // dlopen 成功，但模块调用自己的 native 方法一律
                        // UnsatisfiedLinkError。这里按栈找回真正的模块类，显式调
                        // Runtime.load0(模块类, 路径) 把 so 绑到模块 loader 上。
                        Class<?> caller = moduleCaller();
                        if (caller != null && loadInto(caller, path)) {
                            Diag.log("gload", "已绑定到 " + caller.getName()
                                    + " 的 loader=" + caller.getClassLoader());
                            param.setResult(null);
                        }
                    } catch (Throwable t) {
                        Diag.log("gload", "绑定尝试异常 " + t);
                    } finally {
                        sIn.remove();
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Throwable t = param.getThrowable();
                    if (t == null || sIn.get() != null) {
                        return;
                    }
                    sIn.set(Boolean.TRUE);
                    try {
                        Diag.log("gload", "System." + name + " 失败 " + param.args[0] + " -> " + t);
                    } finally {
                        sIn.remove();
                    }
                }
            });
            Diag.log("xp", "模块 so 加载探针已安装 System." + name);
        } catch (Throwable t) {
            Diag.log("xp", "so 加载探针安装失败 System." + name + " " + t);
        }
    }

    /** 从调用栈里找出「真正调用 System.load 的模块类」。 */
    private static Class<?> moduleCaller() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 3; i < st.length && i < 40; i++) {
                String cn = st[i].getClassName();
                if (cn.startsWith("java.")
                        || cn.startsWith("dalvik.")
                        || cn.startsWith("android.")
                        || cn.startsWith("com.hush.")
                        || cn.startsWith("de.robv.android.xposed.")
                        || cn.startsWith("HushHooker_")
                        || cn.startsWith("com.android.internal.")) {
                    continue;
                }
                Class<?> c = XposedModuleLoader.classInAnyModule(cn);
                if (c == null) {
                    continue;
                }
                return c;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 显式把 so 绑到指定类的 ClassLoader；失败返回 false 走原逻辑。 */
    private static boolean loadInto(Class<?> from, String path) {
        try {
            Method load0 = Runtime.class.getDeclaredMethod("load0", Class.class, String.class);
            load0.setAccessible(true);
            load0.invoke(Runtime.getRuntime(), from, path);
            return true;
        } catch (Throwable t) {
            Diag.log("gload", "Runtime.load0 不可用 " + t);
            return false;
        }
    }
}

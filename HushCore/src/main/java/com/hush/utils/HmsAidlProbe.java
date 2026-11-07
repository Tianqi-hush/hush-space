package com.hush.utils;

import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 2.6.69：抓 SDK 与 HMS Core 之间的 AIDL 事务。
 *
 * <p>2.6.68 想用 logcat 抓 SDK 吞掉的错误码，实测 {@code total=1 kept=0}
 * ——容器进程没有 READ_LOGS，读不到主缓冲区。但两边都在同一个 uid 里，
 * binder 调用本身是我们能看见的，所以改成在 binder 层埋点：</p>
 *
 * <pre>
 * 客户端（com.netease.sky.huawei）
 *   bindService(act=com.huawei.hms.core.aidlservice) -> 容器 ProxyService
 *   onServiceConnected 拿到的 BinderProxy 一调 transact 就记下来：
 *   code / data 大小 / 返回值 / 抛出的异常 / reply 大小
 *
 * 服务端（com.huawei.hwid 容器进程）
 *   BaseExplicitStubService.onBind 返回的包装 Binder（$a）
 *   它的 onTransact 是否收到请求、是否转发给真实 binder、抛了什么
 * </pre>
 *
 * <p>SDK 每 10 秒重绑一次、界面毫无反应，说明<strong>第一次事务就没成功</strong>。
 * 到底是被服务端拒绝（返回 false）、抛异常被 SDK 吃掉，还是根本没发出去，
 * 靠这两端日志即可定位。</p>
 */
public final class HmsAidlProbe {

    /** 只观察从 com.huawei.hwid 绑定回来的 binder，避免记录全进程的海量事务。 */
    private static final Set<Object> WATCHED =
            Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    private static final Map<Object, String> WATCH_NAMES =
            Collections.synchronizedMap(new IdentityHashMap<Object, String>());

    private static boolean sClientHooked;
    private static final Set<ClassLoader> SERVER_DONE =
            Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());

    private HmsAidlProbe() {
    }

    /** 每个 guest 进程启动时调用一次：hook 客户端的 BinderProxy.transact。 */
    public static void install() {
        if (sClientHooked) return;
        sClientHooked = true;
        try {
            Class<?> bp = Class.forName("android.os.BinderProxy");
            Method transact = bp.getDeclaredMethod("transact",
                    int.class, Parcel.class, Parcel.class, int.class);
            XposedBridge.hookMethod(transact, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!WATCHED.contains(param.thisObject)) return;
                    try {
                        String name = WATCH_NAMES.get(param.thisObject);
                        int code = (Integer) param.args[0];
                        Parcel data = (Parcel) param.args[1];
                        Parcel reply = (Parcel) param.args[2];
                        Throwable th = param.getThrowable();
                        Diag.log("aidl", "客户端 transact " + name
                                + " code=" + code
                                + " data=" + (data == null ? -1 : data.dataSize())
                                + " result=" + param.getResult()
                                + " reply=" + (reply == null ? -1 : reply.dataSize())
                                + (th == null ? "" : " ERR=" + th));
                    } catch (Throwable t) {
                        Diag.log("aidl", "客户端记录失败 " + t);
                    }
                }
            });
            Diag.log("aidl", "客户端 BinderProxy.transact 探针已安装");
        } catch (Throwable t) {
            Diag.log("aidl", "客户端探针安装失败 " + t);
        }
        hookInnerConnection();
    }

    private static boolean sInnerHooked;

    /**
     * 2.6.72：服务端 onServiceConnected 到底有没有送到 guest 的
     * ServiceConnection（LoadedApk$ServiceDispatcher$InnerConnection）。
     * 有它才能区分「SDK 收到了 binder 却不用」和「SDK 根本没收到回调」。
     */
    private static void hookInnerConnection() {
        if (sInnerHooked) return;
        sInnerHooked = true;
        try {
            Class<?> k = Class.forName("android.app.LoadedApk$ServiceDispatcher$InnerConnection");
            for (final Method m : k.getDeclaredMethods()) {
                if (!"connected".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object b = null;
                            for (Object a : param.args) {
                                if (a instanceof IBinder) {
                                    b = a;
                                    break;
                                }
                            }
                            Diag.log("svc", "InnerConnection.connected args=" + param.args.length
                                    + " binder=" + (b == null ? "null" : b.getClass().getName())
                                    + (param.getThrowable() == null
                                    ? "" : " ERR=" + param.getThrowable()));
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
            Diag.log("svc", "InnerConnection 探针已安装");
        } catch (Throwable t) {
            Diag.log("svc", "InnerConnection 探针跳过 " + t);
        }
    }

    /** 观察到这个 binder 之后，它上面的每次事务都会进 diag。 */
    public static void watch(IBinder binder, String why) {
        if (binder == null) return;
        if (WATCHED.contains(binder)) return;
        String desc = null;
        try {
            desc = binder.getInterfaceDescriptor();
        } catch (Throwable ignored) {
        }
        WATCH_NAMES.put(binder, (why == null ? "?" : why) + " desc=" + desc);
        WATCHED.add(binder);
        Diag.log("aidl", "开始观察 binder " + why + " desc=" + desc);
    }

    /**
     * 2.6.71：SDK 侧的 AIDL 代理是 Java 类，hook 它的方法能直接看到
     * 「调了哪个方法、返回什么、抛了什么」，比 binder 事务码更好读。
     */
    private static final Set<ClassLoader> PROXY_DONE =
            Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());

    private static final String[] PROXY_CLASSES = {
            "com.huawei.hms.core.aidl.IAIDLInvoke$Stub$Proxy",
            "com.huawei.hms.core.aidl.IInnerAIDLInvoke$Stub$Proxy",
            "com.huawei.hms.kitstore.aidl.IKitFeatureManager$Stub$Proxy",
            "com.huawei.hwid.common.innercall.server.IHwidInnerAIDLInvoke$Stub$Proxy",
    };

    /**
     * 2.6.71：如果容器把 binder 变成了同进程的本地对象，SDK 调的是 Stub 本身
     * （asInterface 会直接返回本地实现），不会走 transact。也把这些 Stub 的
     * 业务方法记录下来。
     */
    private static final String[] STUB_CLASSES = {
            "com.huawei.hms.core.aidl.IAIDLInvoke$Stub",
            "com.huawei.hms.core.aidl.IInnerAIDLInvoke$Stub",
            "com.huawei.hms.kitstore.aidl.IKitFeatureManager$Stub",
            "com.huawei.hwid.common.innercall.server.IHwidInnerAIDLInvoke$Stub",
    };

    public static void installProxy(ClassLoader cl) {
        if (cl == null || PROXY_DONE.contains(cl)) return;
        PROXY_DONE.add(cl);
        for (String cn : PROXY_CLASSES) {
            try {
                Class<?> k = Class.forName(cn, false, cl);
                for (final Method m : k.getDeclaredMethods()) {
                    if (m.getName().startsWith("$")) continue;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Diag.log("aidl", "代理调用 " + shortName(param.thisObject)
                                        + "." + param.method.getName()
                                        + " args=" + param.args.length
                                        + " -> " + param.getResult()
                                        + (param.getThrowable() == null
                                        ? "" : " ERR=" + param.getThrowable()));
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
                Diag.log("aidl", "代理探针已安装 " + cn);
            } catch (Throwable t) {
                Diag.log("aidl", "代理探针跳过 " + cn + " " + t);
            }
        }
        for (String cn : STUB_CLASSES) {
            try {
                Class<?> k = Class.forName(cn, false, cl);
                for (final Method m : k.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                    String n = m.getName();
                    if ("asInterface".equals(n) || "asBinder".equals(n)
                            || "getInterfaceDescriptor".equals(n)) continue;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Diag.log("aidl", "本地实现调用 " + shortName(param.thisObject)
                                        + "." + param.method.getName()
                                        + " args=" + param.args.length
                                        + " -> " + param.getResult()
                                        + (param.getThrowable() == null
                                        ? "" : " ERR=" + param.getThrowable()));
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
                Diag.log("aidl", "本地实现探针已安装 " + cn);
            } catch (Throwable t) {
                Diag.log("aidl", "本地实现探针跳过 " + cn + " " + t);
            }
        }
    }

    private static String shortName(Object o) {
        if (o == null) return "null";
        String n = o.getClass().getName();
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(i + 1);
    }

    private static final Set<Class<?>> SERVER_TAPPED =
            Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean>());

    /**
     * 2.6.74：直接盯住服务端真正返回给 SDK 的那个 binder。
     * 2.6.73 已经证明请求发出去了（code=2 in=952/756 返回 true），但服务端
     * 有没有收到、收到后抛没抛异常，之前 hook 的类根本没命中。这里在
     * AppServiceDispatcher.onBind 拿到真实 binder 的一刻，按它自己的类
     * hook onTransact，并把请求里的可读字符串打出来（华为的 Message
     * 会把方法名，如 checkHmsCoreVersion / hwid.signIn 直接写在 parcel 里）。
     */
    public static void tapServerBinder(IBinder binder, String why) {
        if (binder == null) return;
        try {
            final String cls = binder.getClass().getName();
            Diag.log("aidl", "onBind " + why + " -> binder=" + cls);
            if (!cls.startsWith("com.huawei")) return;
            if (!SERVER_TAPPED.add(binder.getClass())) return;
            // 2.6.78：onTransact 往往声明在父类（IGameUiService$Stub 等），
            // 只看 getDeclaredMethods() 会漏掉，导致 2.6.77 里 GameUiService$b
            // 收了请求却一条日志都没有。这里沿继承链逐级挂。
            int hooked = 0;
            Class<?> k = binder.getClass();
            while (k != null && k != Object.class) {
                for (final Method m : k.getDeclaredMethods()) {
                    if (!"onTransact".equals(m.getName())) continue;
                    hooked++;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                int code = (Integer) param.args[0];
                                Parcel data = (Parcel) param.args[1];
                                Diag.log("aidl", "服务端 onTransact " + cls + " code=" + code
                                        + " in=" + (data == null ? -1 : data.dataSize())
                                        + " str=" + parcelStrings(data));
                            } catch (Throwable ignored) {
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Parcel reply = (Parcel) param.args[2];
                                Diag.log("aidl", "服务端 onTransact 返回 " + cls
                                        + " code=" + param.args[0]
                                        + " result=" + param.getResult()
                                        + " out=" + (reply == null ? -1 : reply.dataSize())
                                        + (param.getThrowable() == null
                                        ? "" : " ERR=" + param.getThrowable()));
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
                k = k.getSuperclass();
            }
            Diag.log("aidl", "服务端 binder 已挂钩 " + cls + " 共" + hooked + "处");
        } catch (Throwable t) {
            Diag.log("aidl", "服务端 binder 挂钩失败 " + t);
        }
    }

    /** 把 Parcel 里的可打印字符串捞出来，用来认方法名。 */
    static String parcelStrings(Parcel p) {
        if (p == null) return "-";
        try {
            byte[] b = p.marshall();
            StringBuilder out = new StringBuilder();
            StringBuilder cur = new StringBuilder();
            int kept = 0;
            for (byte x : b) {
                char c = (char) x;
                if (c >= 32 && c < 127) {
                    cur.append(c);
                } else {
                    if (cur.length() >= 5 && kept < 8) {
                        out.append('|').append(cur);
                        kept++;
                    }
                    cur.setLength(0);
                }
            }
            if (cur.length() >= 5 && kept < 8) {
                out.append('|').append(cur);
            }
            if (out.length() > 260) {
                return out.substring(0, 260) + "...";
            }
            return out.length() == 0 ? "-" : out.toString();
        } catch (Throwable t) {
            return "(" + t + ")";
        }
    }

    /** HMS 进程里 hook 服务端包装 Binder 的 onTransact，看请求到底有没有到。 */
    public static void installServer(ClassLoader cl) {
        if (cl == null || SERVER_DONE.contains(cl)) return;
        SERVER_DONE.add(cl);
        try {
            Class<?> base = Class.forName(
                    "com.huawei.hms.runtimekit.container.service.BaseExplicitStubService", false, cl);
            Class<?> wrapper = Class.forName(
                    "com.huawei.hms.runtimekit.container.service.BaseExplicitStubService$a", false, cl);

            // onBind 返回的是包装 Binder（$a），真实 binder 为 null 时它也会照常返回，
            // 客户端看到 binder=true，但其实没人能处理请求 —— 这正是要区分的点。
            for (Method m : base.getDeclaredMethods()) {
                if (!"onBind".equals(m.getName()) || m.getParameterTypes().length != 1) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object r = param.getResult();
                        Diag.log("aidl", "服务端 onBind " + param.thisObject.getClass().getSimpleName()
                                + " -> " + (r == null ? "null" : r.getClass().getName()));
                    }
                });
            }

            for (final Method m : wrapper.getDeclaredMethods()) {
                if (!"onTransact".equals(m.getName()) || m.getParameterTypes().length != 4) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Throwable th = param.getThrowable();
                            Diag.log("aidl", "服务端 onTransact code=" + param.args[0]
                                    + " result=" + param.getResult()
                                    + " realBinder=" + describeRealBinder(param.thisObject)
                                    + (th == null ? "" : " ERR=" + th));
                        } catch (Throwable t) {
                            Diag.log("aidl", "服务端记录失败 " + t);
                        }
                    }
                });
            }
            Diag.log("aidl", "服务端 onTransact 探针已安装 CL=" + System.identityHashCode(cl));
        } catch (Throwable t) {
            // 普通 App 的 CL 里没有 runtimekit 的类，这里失败是正常的
            Diag.log("aidl", "服务端探针跳过 " + t);
        }
    }

    /** 读包装 Binder 里的真实 binder 字段（$a.f766b）。 */
    private static String describeRealBinder(Object wrapper) {
        Class<?> k = wrapper.getClass();
        while (k != null) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() != IBinder.class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(wrapper);
                    return v == null ? "null" : v.getClass().getName();
                } catch (Throwable ignored) {
                }
            }
            k = k.getSuperclass();
        }
        return "?";
    }
}

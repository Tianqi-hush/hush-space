package com.hush.utils;

import android.os.IBinder;

import com.hush.fake.service.IActivityTaskManagerProxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 接管 {@code android.app.IActivityClientController}，让
 * {@code Activity.getCallingPackage()} 返回虚拟调用方包名。
 *
 * <p>Android 17（SDK 37）的取数链路已经变了：
 *
 * <pre>
 *   Activity.getCallingPackage()
 *     -> ActivityClient.getInstance().getCallingPackage(mToken)
 *       -> IActivityClientController.getCallingPackage(token)   // Binder
 * </pre>
 *
 * <p>这条链完全绕开了 {@code ActivityTaskManager}，所以以前写在
 * Activity / ActivityClientRecord 上的字段、以及 ATM 的
 * {@code getLaunchedFromPackage} hook 都不会被读到 —— 日志里
 * {@code mCallingPackageName} 一直是 {@code com.hush.space} 就是这个原因。
 *
 * <p>这里把 {@code IActivityClientController} 换成动态代理，只拦
 * {@code getCallingPackage} / {@code getLaunchedFromPackage} 两个方法，
 * token 命中我们记录的「真实调用方」就返回它，其余原样透传。
 *
 * <p>两条安装路径都做，防止被系统覆盖：
 * <ol>
 *   <li>{@code IActivityTaskManager.getActivityClientController()} hook ——
 *       ActivityThread 重新取控制器时拿到的也是代理；</li>
 *   <li>直接调 {@code ActivityClient.setActivityClientController(proxy)} 预置缓存。</li>
 * </ol>
 */
public final class ActivityClientHook {

    private static volatile boolean sInstalled;
    private static volatile boolean sTried;
    private static volatile Object sProxy;

    private ActivityClientHook() {
    }

    /** 包一层代理；已经是代理或类型不对就原样返回。 */
    public static Object wrap(Object base) {
        if (base == null) return null;
        Object cached = sProxy;
        if (cached != null) return cached;
        try {
            Class<?> ctlItf = Class.forName("android.app.IActivityClientController");
            if (!ctlItf.isInstance(base)) return base;
            if (Proxy.isProxyClass(base.getClass())) return base;
            synchronized (ActivityClientHook.class) {
                if (sProxy != null) return sProxy;
                sProxy = Proxy.newProxyInstance(
                        ctlItf.getClassLoader(),
                        new Class<?>[]{ctlItf},
                        new Handler(base));
                return sProxy;
            }
        } catch (Throwable t) {
            Diag.log("caller", "ActivityClient 包装失败 " + t);
            return base;
        }
    }

    public static void install() {
        if (sInstalled || sTried) return;
        synchronized (ActivityClientHook.class) {
            if (sInstalled || sTried) return;
            sTried = true;
            try {
                Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
                Class<?> atmItf = Class.forName("android.app.IActivityTaskManager");
                Class<?> clientClass = Class.forName("android.app.ActivityClient");

                Object atm = atmClass.getMethod("getService").invoke(null);
                if (atm == null) {
                    Diag.log("caller", "ActivityClient 接管失败: atm=null");
                    return;
                }
                Object base = atmItf.getMethod("getActivityClientController").invoke(atm);
                Object proxy = wrap(base);
                if (proxy == null) {
                    Diag.log("caller", "ActivityClient 接管失败: controller=null");
                    return;
                }

                Method setter = null;
                for (Method m : clientClass.getDeclaredMethods()) {
                    if (m.getName().equals("setActivityClientController")
                            && m.getParameterTypes().length == 1) {
                        setter = m;
                        break;
                    }
                }
                if (setter == null) {
                    Diag.log("caller", "ActivityClient 接管失败: 无 setter");
                    return;
                }
                setter.setAccessible(true);
                setter.invoke(null, proxy);
                sInstalled = true;
                Diag.log("caller", "ActivityClient 控制器已接管");
            } catch (Throwable t) {
                Diag.log("caller", "ActivityClient 接管失败 " + t);
            }
        }
    }

    private static final class Handler implements InvocationHandler {

        private final Object mBase;

        Handler(Object base) {
            mBase = base;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (("getCallingPackage".equals(name) || "getLaunchedFromPackage".equals(name))
                    && args != null && args.length == 1 && args[0] instanceof IBinder) {
                String pkg = IActivityTaskManagerProxy.callerOf((IBinder) args[0]);
                if (pkg != null) {
                    Diag.log("caller", name + " -> " + pkg + " token=" + args[0]);
                    return pkg;
                }
            }
            try {
                return method.invoke(mBase, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw (cause != null ? cause : e);
            }
        }
    }
}

package com.hush.fake.service;

import android.app.ActivityManager;

import java.lang.reflect.Method;

import com.hush.env.android.app.BRActivityTaskManager;
import com.hush.env.android.app.BRIActivityTaskManagerStub;
import com.hush.env.android.os.BRServiceManager;
import com.hush.env.android.util.BRSingleton;

import com.hush.fake.hook.BinderInvocationStub;
import com.hush.fake.hook.MethodHook;
import com.hush.fake.hook.ProxyMethod;
import com.hush.fake.hook.ScanClass;
import com.hush.utils.compat.TaskDescriptionCompat;

/**
 * Created by @officialakshit01
 * Android 10 (API 29) -> Android 16 (API 35+)
 * Fully compatible & crash-safe
 */
@ScanClass(ActivityManagerCommonProxy.class)
public class IActivityTaskManagerProxy extends BinderInvocationStub {

    public static final String TAG = "ActivityTaskManager";

    public IActivityTaskManagerProxy() {
        super(BRServiceManager.get().getService("activity_task"));
    }

    @Override
    protected Object getWho() {
        return BRIActivityTaskManagerStub.get().asInterface(BRServiceManager.get().getService("activity_task"));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("activity_task");
        BRActivityTaskManager.get().getService();
        Object singleton = BRActivityTaskManager.get().IActivityTaskManagerSingleton();
        BRSingleton.get(singleton)._set_mInstance(BRIActivityTaskManagerStub.get().asInterface(this));
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("setTaskDescription")
    public static class SetTaskDescription extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {

            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof ActivityManager.TaskDescription) {
                        ActivityManager.TaskDescription td = (ActivityManager.TaskDescription) args[i];
                        args[i] = TaskDescriptionCompat.fix(td);
                        break;
                    }
                }
            }

            return method.invoke(who, args);
        }
    }

    /**
     * Android 17 起 {@code Activity.getCallingPackage()} 走的是
     * {@code ActivityClient -> IActivityClientController}，不再经过 ATM。
     * 这里把控制器包一层，交给 {@link com.hush.utils.ActivityClientHook} 代理。
     */
    @ProxyMethod("getActivityClientController")
    public static class GetActivityClientController extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object base = method.invoke(who, args);
            try {
                return com.hush.utils.ActivityClientHook.wrap(base);
            } catch (Throwable t) {
                return base;
            }
        }
    }

    /** token -> 发起该 Activity 的虚拟包名。 */
    private static final java.util.concurrent.ConcurrentHashMap<android.os.IBinder, String> sCallers =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void recordCaller(android.os.IBinder token, String pkg) {
        if (token == null || pkg == null || pkg.isEmpty()) return;
        // 防止异常场景下无限增长：超过 512 条直接清空，只是兜底信息。
        if (sCallers.size() > 512) sCallers.clear();
        sCallers.put(token, pkg);
        sLastCaller = pkg;
        sLastCallerAt = android.os.SystemClock.uptimeMillis();
    }

    /** 最近一次启动的调用方，token 对不上时（PendingIntent 等）兜底用。 */
    private static volatile String sLastCaller;
    private static volatile long sLastCallerAt;

    /** Android 17 的 ActivityClient 走的就是这张表。 */
    public static String callerOf(android.os.IBinder token) {
        if (token == null) return null;
        try {
            String pkg = sCallers.get(token);
            if (pkg != null) return pkg;
        } catch (Throwable ignored) {
        }
        // token 没记录：多半是 PendingIntent / 系统转发的启动。启动是刚刚发生的，
        // 3 秒内最近一次记录的调用方基本就是它。
        String last = sLastCaller;
        if (last != null && android.os.SystemClock.uptimeMillis() - sLastCallerAt < 3000L) {
            com.hush.utils.Diag.log("caller", "token 未命中，按最近调用方兜底 " + last);
            return last;
        }
        return null;
    }

    /**
     * {@code Activity.getCallingPackage()} 在 mLaunchedFromPackage 为空时会退到
     * {@code ActivityTaskManager.getLaunchedFromPackage(token)}。
     *
     * <p>正常情况下 HCallbackStub 已经把 record.launchedFromPackage 改好了，
     * 这里只是给改不了字段的 ROM 兜底。
     */
    @ProxyMethod("getLaunchedFromPackage")
    public static class GetLaunchedFromPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object result = method.invoke(who, args);
            try {
                if (args != null && args.length > 0 && args[0] instanceof android.os.IBinder) {
                    String pkg = sCallers.get((android.os.IBinder) args[0]);
                    if (pkg != null) return pkg;
                }
            } catch (Throwable ignored) {
            }
            return result;
        }
    }
}
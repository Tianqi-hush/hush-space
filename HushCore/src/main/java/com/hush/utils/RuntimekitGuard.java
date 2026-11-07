package com.hush.utils;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * runtimekit 跳板页的「二次机会」。
 *
 * <p>HMS 的登录链路是：发起页 → {@code HMSKitStandardC2NEStubActivity1}（1×1
 * 透明跳板）→ 真实页面。跳板页 {@code onCreate} 只做一件事：拿 intent 的 action
 * 去 kams 的 {@code verifySession()} 查 kit 信息，查不到就
 * {@code finishWithTryCatch()} —— 不抛异常，整条 Activity 栈跟着退掉，用户看到
 * 的就是「进界面一秒闪退」。</p>
 *
 * <p>容器里 kams（{@code com.huawei.hwid.core} 的 {@code HMSKitCoreNewService}）
 * 是临时拉起的，跳板页经常比服务就绪早一两百毫秒，第一次查询必然落空。真机上
 * 这个服务常驻，没有这个空档。</p>
 *
 * <p>2.6.26 先在 onCreate 前等 400ms 把空档填上；万一还是失败，这里再兜一次：
 * 拦下跳板页的第一次 {@code finishActivity}，350ms 后反射重跑
 * {@code ContainerActivity.loadKitForActivity(Bundle)}。这次服务一定已经在了，
 * 查得到 session 就会正常拉起真实页面；再失败才真的放行 finish。</p>
 */
public final class RuntimekitGuard {

    private static final Map<IBinder, Entry> STUBS = new ConcurrentHashMap<>();

    private static final class Entry {
        final WeakReference<Activity> activity;
        final long created;
        boolean retried;

        Entry(Activity a) {
            this.activity = new WeakReference<>(a);
            this.created = SystemClock.uptimeMillis();
        }
    }

    private RuntimekitGuard() {
    }

    public static boolean isStub(Activity a) {
        return a != null && a.getClass().getName().startsWith("com.huawei.hms.runtimekit.stub.");
    }

    /** 跳板页 onCreate 之前登记，记下 token，供 finish 拦截时匹配。 */
    public static void watch(Activity a) {
        try {
            if (!isStub(a)) return;
            IBinder token = tokenOf(a);
            if (token == null) return;
            if (STUBS.size() > 64) STUBS.clear();
            STUBS.put(token, new Entry(a));
        } catch (Throwable ignored) {
        }
    }

    /**
     * @return true 表示这次 finish 要拦下来（稍后重试），false 表示正常放行。
     */
    public static boolean interceptFinish(IBinder token) {
        if (token == null) return false;
        Entry e = STUBS.get(token);
        if (e == null) return false;
        // 已经重试过，或者这个跳板页已经活了好几秒（不是「起来就没」那种），
        // 都按正常关闭处理。
        if (e.retried || SystemClock.uptimeMillis() - e.created > 4000) {
            STUBS.remove(token);
            return false;
        }
        e.retried = true;
        final Activity a = e.activity.get();
        Diag.log("act", "stub 首次 finish 被拦下，350ms 后重试");
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    retry(a);
                }
            }, 350);
        } catch (Throwable t) {
            Diag.log("act", "stub 重试排期失败 " + t);
            return false;
        }
        return true;
    }

    private static void retry(Activity a) {
        if (a == null) return;
        try {
            Method m = null;
            for (Class<?> c = a.getClass(); c != null && m == null; c = c.getSuperclass()) {
                try {
                    m = c.getDeclaredMethod("loadKitForActivity", Bundle.class);
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (m == null) {
                Diag.log("act", "stub 重试放弃：找不到 loadKitForActivity");
                a.finish();
                return;
            }
            m.setAccessible(true);
            Object r = m.invoke(a, new Object[]{null});
            Diag.log("act", "stub 重试 loadKitForActivity -> " + r);
            if (!Boolean.TRUE.equals(r)) {
                a.finish();
            }
        } catch (Throwable t) {
            Diag.log("act", "stub 重试异常 " + t);
            try {
                a.finish();
            } catch (Throwable ignored) {
            }
        }
    }

    private static IBinder tokenOf(Activity a) {
        IBinder fallback = null;
        for (Field f : Activity.class.getDeclaredFields()) {
            if (!IBinder.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(a);
                if (!(v instanceof IBinder)) continue;
                if (f.getName().toLowerCase().contains("token")) return (IBinder) v;
                if (fallback == null) fallback = (IBinder) v;
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }
}

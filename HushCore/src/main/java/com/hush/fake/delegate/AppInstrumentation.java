package com.hush.fake.delegate;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.util.Log;

import com.hush.env.com.cosmos.apm.framework.page.BRActivityLifeCycleHelper$ApplicationInstrumentation;
import java.lang.reflect.Field;

import com.hush.env.android.app.BRActivity;
import com.hush.env.android.app.BRActivityThread;

import com.hush.HushCore;
import com.hush.app.BActivityThread;
import com.hush.fake.hook.HookManager;
import com.hush.fake.hook.IInjectHook;
import com.hush.fake.service.HCallbackStub;
import com.hush.fake.service.IActivityClientProxy;
import com.hush.utils.HackAppUtils;
import com.hush.utils.Slog;
import com.hush.utils.compat.ActivityCompat;
import com.hush.utils.compat.ActivityManagerCompat;
import com.hush.utils.compat.ContextCompat;

/**
 * AppInstrumentation
 *
 * Wraps the real Instrumentation to hook the virtual application lifecycle.
 * All Facebook OAuth handling is done at the AMS Binder level (ActivityManagerCommonProxy).
 * This class only manages ClassLoader, theme, and orientation fixes for virtual activities.
 */
public final class AppInstrumentation extends BaseInstrumentationDelegate implements IInjectHook {

    private static final String TAG = AppInstrumentation.class.getSimpleName();
    private static final String RIFLE_APP_CLASS =
            "com.cosmos.apm.framework.page.ActivityLifeCycleHelper$ApplicationInstrumentation";

    private static AppInstrumentation sAppInstrumentation;
    private ClassLoader delegateAppClassLoader;

    private AppInstrumentation() {}

    public static AppInstrumentation get() {
        if (sAppInstrumentation == null) {
            synchronized (AppInstrumentation.class) {
                if (sAppInstrumentation == null) sAppInstrumentation = new AppInstrumentation();
            }
        }
        return sAppInstrumentation;
    }

    public ClassLoader getDelegateAppClassLoader() { return delegateAppClassLoader; }

    @Override
    public void injectHook() {
        try {
            Instrumentation curr = getCurrInstrumentation();
            if (curr == this || checkInstrumentation(curr)) return;
            mBaseInstrumentation = curr;
            BRActivityThread.get(HushCore.mainThread())._set_mInstrumentation(this);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void fixRifleHook() {
        Instrumentation curr = getCurrInstrumentation();
        if (curr instanceof AppInstrumentation) return;
        if (RIFLE_APP_CLASS.equals(curr.getClass().getName())) {
            AppInstrumentation ai = (AppInstrumentation)
                    BRActivityLifeCycleHelper$ApplicationInstrumentation.get(curr).mBase();
            if (ai != null) mBaseInstrumentation = ai.mBaseInstrumentation;
            BRActivityThread.get(HushCore.mainThread())._set_mInstrumentation(this);
        }
    }

    public void fixInstrumentationAfterApplicationOnCreate() {
        Instrumentation curr = getCurrInstrumentation();
        if (curr instanceof AppInstrumentation) return;
        for (Field f : curr.getClass().getDeclaredFields()) {
            if (!Instrumentation.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            try {
                Object obj = f.get(curr);
                if (obj instanceof AppInstrumentation) {
                    Instrumentation base = ((AppInstrumentation) obj).mBaseInstrumentation;
                    f.set(curr, base);
                    ((AppInstrumentation) obj).mBaseInstrumentation = curr;
                    BRActivityThread.get(HushCore.mainThread())._set_mInstrumentation(obj);
                }
            } catch (Exception ignored) {}
            break;
        }
    }

    public Instrumentation getCurrInstrumentation() {
        return BRActivityThread.get(HushCore.mainThread()).mInstrumentation();
    }

    @Override
    public boolean isBadEnv() { return !checkInstrumentation(getCurrInstrumentation()); }

    private boolean checkInstrumentation(Instrumentation ins) {
        if (ins instanceof AppInstrumentation) return true;
        Class<?> cls = ins.getClass();
        if (Instrumentation.class.equals(cls)) return false;
        do {
            for (Field f : cls.getDeclaredFields()) {
                if (!Instrumentation.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                try { if (f.get(ins) instanceof AppInstrumentation) return true; }
                catch (Exception e) { return false; }
            }
            cls = cls.getSuperclass();
        } while (!Instrumentation.class.equals(cls));
        return false;
    }

    private void checkHCallback() { HookManager.get().checkEnv(HCallbackStub.class); }

    /**
     * 把 target intent 里带的调用方虚拟包名写进 Activity.mLaunchedFromPackage。
     *
     * <p>{@code Activity.getCallingPackage()} 读这个字段，HMS 的 ForegroundBus
     * 用它跟请求里的 cpPackageName 比对，不一致就返回 907135000。
     *
     * <p>HCallbackStub 里改 ActivityClientRecord 只在 record 已经建立时有效；
     * Android 12+ 的 LaunchActivityItem 在 record 还没进 mActivities 时就执行了，
     * 会拿不到。Instrumentation 在 onCreate 之前一定会经过，是最后的可靠时点。
     */
    private void applyCallingPackage(Activity activity) {
        try {
            Intent intent = activity.getIntent();
            if (intent == null) return;
            String caller = intent.getStringExtra(
                    com.hush.proxy.record.ProxyActivityRecord.EXTRA_CALLER_PKG);
            if (caller == null || caller.isEmpty()) return;
            intent.removeExtra(com.hush.proxy.record.ProxyActivityRecord.EXTRA_CALLER_PKG);
            // Android 17 的 getCallingPackage() 走 ActivityClient -> IActivityClientController，
            // 只有 token 表能影响它；字段写不写都不影响结果，但保留给低版本 ROM 兜底。
            try {
                com.hush.fake.service.IActivityTaskManagerProxy.recordCaller(
                        com.hush.utils.CallerFix.tokenOf(activity), caller);
            } catch (Throwable ignored) {
            }
            // 字段名 / 存放位置各 ROM 不一样（Android 17 上
            // Activity.mLaunchedFromPackage 直接没了），统一交给 CallerFix
            // 按「精确字段名 → 按宿主包名反查」两路写，并回读校验。
            com.hush.utils.CallerFix.applyToActivity(activity, caller);
        } catch (Throwable t) {
            com.hush.utils.Diag.log("caller", "设置 activity 调用方失败 " + t);
        }
    }

    private void checkActivity(Activity activity) {
        // 必须在任何 guest 代码调用 getCallingPackage() 之前接管控制器。
        try {
            com.hush.utils.ActivityClientHook.install();
        } catch (Throwable ignored) {
        }
        applyCallingPackage(activity);
        HackAppUtils.enableQQLogOutput(activity.getPackageName(), activity.getClassLoader());
        checkHCallback();
        HookManager.get().checkEnv(IActivityClientProxy.class);
        ActivityInfo info = BRActivity.get(activity).mActivityInfo();
        ContextCompat.fix(activity);
        ActivityCompat.fix(activity);
        if (info.theme != 0) activity.getTheme().applyStyle(info.theme, true);
        ActivityManagerCompat.setActivityOrientation(activity, info.screenOrientation);
        try {
            if (activity.getClass().getName().endsWith(".core.activity.UiJumpActivity")) {
                probeJumpDelegate(activity);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 跳板页自检：把 delegate 名和 delegate 实例的创建结果直接打出来。
     *
     * <p>UiJumpActivity.onCreate 只会做一件事——按
     * {@code intent.extra.hms.core.DELEGATE_NAME} 找 delegate，找不到就
     * setResult(1) + finish，页面上什么都不显示。它自己把异常吞了，外面
     * 只能看到「点开就退」。这里在 onCreate 之前把同样的查找过程走一遍，
     * 成功与否、抛了什么异常，全部落到日志里。</p>
     */
    private static void probeJumpDelegate(Activity a) {
        StringBuilder sb = new StringBuilder("probe UiJump ");
        try {
            Intent it = a.getIntent();
            String name = it == null ? null : it.getStringExtra("intent.extra.hms.core.DELEGATE_NAME");
            sb.append("delegateName=").append(name == null ? "<null>" : "'" + name + "'").append(' ');
            Class<?> center = Class.forName("Y0.b", false, a.getClassLoader());
            Object c = center.getMethod("b").invoke(null);
            sb.append("center=").append(c != null).append(' ');
            if (c != null && name != null && !name.isEmpty()) {
                Object d = center.getMethod("c", String.class).invoke(c, name);
                sb.append("instance=").append(d == null ? "NULL" : d.getClass().getName());
            }
        } catch (Throwable t) {
            sb.append("ERR ").append(com.hush.utils.Diag.stack(t));
        }
        com.hush.utils.Diag.log("act", sb.toString());
    }

    @Override
    public Application newApplication(ClassLoader cl, String className, Context ctx)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ContextCompat.fix(ctx);
        BActivityThread.currentActivityThread().loadXposed(ctx);
        delegateAppClassLoader = ctx.getClassLoader();
        return super.newApplication(cl, className, ctx);
    }

    @Override
    public void callActivityOnCreate(Activity a, Bundle b, PersistableBundle p) {
        com.hush.utils.StubSession.fixActivity(a);
        traceActivity("create", a);
        checkActivity(a);
        // 2.6.28 实测：直连会把 HMS 自己本来能跑通的 stubexplicit 页提前 finish，
        // 连带把整条链掐断（账号页 post-create 就 finishing=true）。撤回直连，
        // 只保留 StubSession 的 session 对齐，让 HMS 走它自己的流程。
        awaitRuntimekitReady(a);
        // 2.6.36：prewarm 在 2.6.33 被撤，是因为当时它内部拿 KitLoaderManager
        // 走的是 Class.forName（宿主 CL），必然 ClassNotFound，等于什么都没做，
        // 反而让人以为「预热无效」。2.6.35 的 Toast 把这个 bug 曝光了，现已改成
        // 用跳板页自己的 ClassLoader。恢复调用：在 HMS 的 loadKitForActivity
        // 之前先把 session 里的路径改对、把 kit 的 dex/Application 建起来，
        // 它自己那次 loadKit 就会命中缓存直接成功。
        com.hush.utils.StubLauncher.prewarm(a);
        long t0 = android.os.SystemClock.uptimeMillis();
        try {
            super.callActivityOnCreate(a, b, p);
            tracePostCreate(a);
            com.hush.utils.StubLauncher.rescue(a);
        } catch (Throwable e) {
            // 登录页这种「起来就没、日志里一条异常都没有」的，异常基本都被
            // HMS 自己的 try/catch 吞了；Instrumentation 这一层是最后能看到
            // 真实堆栈的地方。
            com.hush.utils.Diag.log("act", "create FAILED " + (a == null ? "null" : a.getClass().getName())
                    + "\n" + com.hush.utils.Diag.stack(e));
            throw e;
        } finally {
            long cost = android.os.SystemClock.uptimeMillis() - t0;
            if (cost > 200) {
                com.hush.utils.Diag.log("act", "create slow " + (a == null ? "null" : a.getClass().getName())
                        + " cost=" + cost + "ms");
            }
        }
    }

    @Override
    public void callActivityOnCreate(Activity a, Bundle b) {
        com.hush.utils.StubSession.fixActivity(a);
        traceActivity("create", a);
        checkActivity(a);
        // 2.6.28 实测：直连会把 HMS 自己本来能跑通的 stubexplicit 页提前 finish，
        // 连带把整条链掐断（账号页 post-create 就 finishing=true）。撤回直连，
        // 只保留 StubSession 的 session 对齐，让 HMS 走它自己的流程。
        awaitRuntimekitReady(a);
        // 同三参版本，见上。prewarm 内部全程 try-catch，失败只写日志，绝不会
        // 影响 Activity 生命周期（不会 finish、不会 startActivity）。
        com.hush.utils.StubLauncher.prewarm(a);
        try {
            super.callActivityOnCreate(a, b);
            tracePostCreate(a);
            com.hush.utils.StubLauncher.rescue(a);
        } catch (Throwable e) {
            com.hush.utils.Diag.log("act", "create FAILED " + (a == null ? "null" : a.getClass().getName())
                    + "\n" + com.hush.utils.Diag.stack(e));
            throw e;
        }
    }

    /**
     * 记录 Activity 真实运行的类名。
     *
     * <p>HMS 的 kit 有一层 runtimekit 代理（清单里声明的是
     * {@code com.huawei.hms.runtimekit.stubexplicit.*}，运行时才换成 kit 里的
     * 真实类）。「华为账号点开一两秒就闪退」到底是代理没换成、还是真实类自己
     * finish，只看清单和调用方日志分不出来，这里把最终跑起来的类名钉死在日志里。</p>
     */
    /**
     * runtimekit 跳板页进 onCreate 之前，先等 kams 服务把 session 表建好。
     *
     * <p>HMS 的登录链路是这样跳的：发起页 → {@code HMSKitStandardC2NEStubActivity1}
     * （1×1 透明跳板）→ 真实页面。跳板页 onCreate 只做一件事：拿 intent 的 action
     * 去 kams 的 {@code verifySession()} 查 kit 信息，查不到就 {@code finish()}。</p>
     *
     * <p>kams 是 {@code com.huawei.hwid.core} 里的 {@code HMSKitCoreNewService}，
     * 真机上这个进程常驻，服务一直在，session 随注册随用。容器里这个服务是
     * 临时拉起来的，2.6.25 日志里能看到时间差：</p>
     * <pre>
     *   10:54:30.235  跳板页 create
     *   10:54:30.281  跳板页 finishing=true（verifySession 返回 null）
     *   10:54:30.418  hwid.core bindService HMSKitCoreNewService
     *   10:54:30.420  onServiceConnected
     * </pre>
     *
     * <p>跳板页比服务就绪早了 180ms，当然查不到。真机上永远不会有这个空档。</p>
     *
     * <p>这里在 onCreate 前等 400ms 把空档填上。只对 runtimekit 的 stub 生效，
     * 其他 Activity 一秒都不多等。</p>
     */
    private static void awaitRuntimekitReady(Activity a) {
        try {
            if (a == null) return;
            String cn = a.getClass().getName();
            // 两类跳板：
            //   runtimekit.stub.*        —— 账号页内部那一串（登录引导 → 登录页 → …）
            //   core.activity.UiJumpActivity —— 应用市场 / 游戏 SDK 跨进程拉起账号页
            // 两者都是「起来就问服务要数据，要不到就 finish」，服务慢一点都不行。
            boolean stub = cn.startsWith("com.huawei.hms.runtimekit.stub.");
            boolean uiJump = "com.huawei.hms.core.activity.UiJumpActivity".equals(cn);
            if (!stub && !uiJump) return;
            if (stub) {
                // 2.6.27 起跳板页在 StubLauncher 里已经做过 verify 轮询（最多 1.2s），
                // 走到这里说明真的查不到，再睡 400ms 也没用，直接放行。
                com.hush.utils.RuntimekitGuard.watch(a);
                return;
            }
            long t0 = android.os.SystemClock.uptimeMillis();
            android.os.SystemClock.sleep(400);
            com.hush.utils.Diag.log("act", "stub 等 kams 就绪 " + cn
                    + " cost=" + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
        } catch (Throwable ignored) {
        }
    }

    private static void traceActivity(String what, Activity a) {
        try {
            Intent it = a == null ? null : a.getIntent();
            String cmp = "";
            if (it != null) {
                cmp = " cmp=" + it.getComponent()
                        + " act=" + it.getAction()
                        + " flags=0x" + Integer.toHexString(it.getFlags())
                        + " extras=" + describeExtras(it);
            }
            com.hush.utils.Diag.log("act", what + " " + (a == null ? "null" : a.getClass().getName()) + cmp);
            if ("create".equals(what)) {
                traceNet(a);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 每个 HMS 页面只探一次，避免刷屏。 */
    private static final java.util.Set<String> NET_TRACED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * 2.6.33 新增：登录页「没有网络，无法下一步」的直接取证。
     *
     * <p>HMS 的 {@code NetworkUtil.isNetworkAvailable()} 第一句是
     * {@code checkSelfPermission(ACCESS_NETWORK_STATE)}，被拒就直接返回 false，
     * 连真实的 {@code getActiveNetworkInfo()} 都不调用。这里同时打两者：</p>
     * <pre>
     *   net: checkSelf=0 active=WIFI/true on=…OneKeyLoginOrRegisterActivity
     * </pre>
     * <p>{@code checkSelf} 非 0 就是容器权限层没放行（2.6.33 已修）；
     * {@code active=null} 才是真的没网。</p>
     */
    private static void traceNet(Activity a) {
        if (a == null) return;
        try {
            String cn = a.getClass().getName();
            if (!(cn.startsWith("com.huawei.hms.") || cn.startsWith("com.huawei.hwid")
                    || cn.contains("Login") || cn.contains("hwid"))) {
                return;
            }
            if (!NET_TRACED.add(cn)) return;
            int perm = a.checkPermission("android.permission.ACCESS_NETWORK_STATE",
                    android.os.Process.myPid(), android.os.Process.myUid());
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    a.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            com.hush.utils.Diag.log("net", "checkSelf=" + perm
                    + " active=" + (ni == null ? "null" : ni.getTypeName() + "/" + ni.isConnected())
                    + " on=" + cn);

            // 2.6.38：HMS 真正用的是 guest 的 Context，不是这个 Activity。两边
            // 结果可能不同，必须分开打，否则永远分不清「系统没网」还是「容器里查不到」。
            android.content.Context g = a.getApplicationContext();
            android.net.ConnectivityManager gcm = g == null ? null
                    : (android.net.ConnectivityManager) g.getSystemService(
                    android.content.Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo gni = gcm == null ? null : gcm.getActiveNetworkInfo();
            String capStr = "?";
            if (gcm != null && android.os.Build.VERSION.SDK_INT >= 23) {
                try {
                    android.net.Network n2 = gcm.getActiveNetwork();
                    android.net.NetworkCapabilities nc = n2 == null ? null
                            : gcm.getNetworkCapabilities(n2);
                    capStr = n2 == null ? "net=null"
                            : (nc == null ? "cap=null" : "cap[wifi=" + nc.hasTransport(1)
                            + ",cell=" + nc.hasTransport(0) + ",eth=" + nc.hasTransport(3)
                            + ",valid=" + nc.hasCapability(16) + "]");
                } catch (Throwable t) {
                    capStr = "cap ERR " + t;
                }
            }
            com.hush.utils.Diag.log("net", "guest ctx=" + (g == null ? "null" : g.getClass().getName())
                    + " cm=" + (gcm == null ? "null" : "ok")
                    + " info=" + (gni == null ? "null" : gni.getTypeName() + "/" + gni.isConnected())
                    + " " + capStr);

            // 直接问 HMS 自己的判断函数，它说 0 就是页面显示「无网络」的原因。
            // 2.6.39：上一个版本猜的 z0.m / DeviceUtil 在运行时根本不存在
            // （日志里是 ClassNotFoundException），这次用反编译确认过的真身：
            //   com.huawei.hwid.common.util.NetUtils.getNetworkState(Context)
            // 它就在 AccountKit-base.apk 里，必须用 Application 的 ClassLoader
            // （那份 10 个 dex 的共享 CL），stub 自己的 CL 看不到 kit。
            String verdict = "?";
            try {
                ClassLoader cl = g != null ? g.getClassLoader() : a.getClassLoader();
                Class<?> nu = Class.forName("com.huawei.hwid.common.util.NetUtils", false, cl);
                java.lang.reflect.Method mm = nu.getDeclaredMethod("getNetworkState",
                        android.content.Context.class);
                mm.setAccessible(true);
                Object r = mm.invoke(null, g);
                verdict = "getNetworkState=" + r + (Integer.valueOf(0).equals(r) ? "(无网)" : "");
            } catch (Throwable t) {
                verdict = "N/A " + t.getClass().getSimpleName();
            }
            com.hush.utils.Diag.log("net", "HMS NetUtils -> " + verdict);
        } catch (Throwable t) {
            com.hush.utils.Diag.log("net", "probe failed " + t);
        }
    }

    /**
     * onCreate 一返回就看这个 Activity 是不是已经准备退出。
     *
     * <p>HMS 的跳板页在 onCreate 里判断失败时会 setResult + finish，页面上
     * 什么都没画出来，外面只看到「点开一秒就没了」。把 finishing 状态和
     * 当时的栈一起记下来，就能分清是「onCreate 里自己退的」还是「起来之后
     * 被别的东西收掉的」。</p>
     */
    private static void tracePostCreate(Activity a) {
        if (a == null) return;
        try {
            boolean fin = a.isFinishing();
            String extra = com.hush.utils.StubLauncher.describe(a);
            com.hush.utils.Diag.log("act", "post-create " + a.getClass().getName()
                    + " finishing=" + fin + " destroyed=" + a.isDestroyed()
                    + (extra == null ? "" : " " + extra)
                    + (fin ? "\n" + com.hush.utils.Diag.stack(new Throwable("finish-in-onCreate")) : ""));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把 intent 的 extras 摊平打进日志。
     *
     * <p>HMS 的跳板页（UiJumpActivity 之类）全靠 extra 里塞的 delegate 名和
     * 请求头决定下一步；容器跨进程传 Intent 时如果 Bundle 掉了，页面就会
     * 「create 完 20 毫秒自己 finish」，而且一条异常都没有。以前日志里只有
     * action/flags，看不出 extras 到底在不在，这里补上。</p>
     */
    private static String describeExtras(Intent it) {
        try {
            Bundle b = it.getExtras();
            if (b == null) return "null";
            StringBuilder sb = new StringBuilder("{");
            for (String k : b.keySet()) {
                Object v;
                try {
                    v = b.get(k);
                } catch (Throwable t) {
                    sb.append(k).append("=<").append(t.getClass().getSimpleName()).append(">, ");
                    continue;
                }
                sb.append(k).append('=');
                if (v instanceof Bundle) {
                    sb.append("Bundle").append(((Bundle) v).keySet());
                } else if (v instanceof Intent) {
                    Intent i2 = (Intent) v;
                    sb.append("Intent(").append(i2.getComponent()).append('/').append(i2.getAction()).append(')');
                } else {
                    String s = String.valueOf(v);
                    sb.append(s.length() > 80 ? s.substring(0, 80) + "..." : s);
                }
                sb.append(", ");
            }
            if (sb.length() > 900) sb.setLength(900);
            return sb.append('}').toString();
        } catch (Throwable t) {
            return "err:" + t;
        }
    }

    @Override
    public void callActivityOnActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        try {
            com.hush.utils.Diag.log("res", "onActivityResult "
                    + (activity == null ? "null" : activity.getClass().getName())
                    + " req=" + requestCode + " res=" + resultCode
                    + " data=" + (data == null ? "null" : data.getComponent() + "/" + data.getAction()));
        } catch (Throwable ignored) {
        }
        super.callActivityOnActivityResult(activity, requestCode, resultCode, data);
    }

    @Override
    public void callActivityOnResume(Activity activity) {
        com.hush.utils.StubLauncher.markResumed(activity);
        traceActivity("resume", activity);
        super.callActivityOnResume(activity);
    }

    @Override
    public void callActivityOnPause(Activity activity) {
        traceActivity("pause", activity);
        super.callActivityOnPause(activity);
    }

    @Override
    public void callActivityOnDestroy(Activity activity) {
        try {
            com.hush.utils.Diag.log("act", "destroy " + (activity == null ? "null" : activity.getClass().getName())
                    + " finishing=" + (activity != null && activity.isFinishing()));
        } catch (Throwable ignored) {
        }
        super.callActivityOnDestroy(activity);
    }

    @Override
    public void callApplicationOnCreate(Application app) {
        checkHCallback();
        // 壳的 attachBaseContext 已经执行完，LoadedApk 的 ClassLoader 这时才是
        // 真正能加载业务类的那个。模块里「等壳初始化」的延迟入口在这里补一次。
        try {
            com.hush.core.xposed.XposedModuleLoader.onApplicationCreate(app);
        } catch (Throwable ignored) {
        }
        super.callApplicationOnCreate(app);
    }

    @Override
    public Activity newActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try { return super.newActivity(cl, className, intent); }
        catch (ClassNotFoundException e) { return mBaseInstrumentation.newActivity(cl, className, intent); }
    }

    @Override
    public ActivityResult execStartActivity(Context ctx, IBinder b0, IBinder b1,
            Activity activity, Intent intent, int i, Bundle bundle) throws Throwable {
        com.hush.utils.StubSession.fix(intent);
        traceStart("activity", activity, intent, i);
        return super.execStartActivity(ctx, b0, b1, activity, intent, i, bundle);
    }

    @Override
    public ActivityResult execStartActivity(Context ctx, IBinder b0, IBinder b1,
            android.app.Fragment fragment, Intent intent, int i) throws Throwable {
        com.hush.utils.StubSession.fix(intent);
        return super.execStartActivity(ctx, b0, b1, fragment, intent, i);
    }

    @Override
    public ActivityResult execStartActivity(Context ctx, IBinder b0, IBinder b1,
            android.app.Fragment fragment, Intent intent, int i, Bundle bundle) throws Throwable {
        com.hush.utils.StubSession.fix(intent);
        return super.execStartActivity(ctx, b0, b1, fragment, intent, i, bundle);
    }

    @Override
    public ActivityResult execStartActivity(Context ctx, IBinder b0, IBinder b1,
            String str, Intent intent, int i, Bundle bundle) throws Throwable {
        com.hush.utils.StubSession.fix(intent);
        return super.execStartActivity(ctx, b0, b1, str, intent, i, bundle);
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public ActivityResult execStartActivity(Context ctx, IBinder b0, IBinder b1,
            Activity activity, Intent intent, int i, Bundle bundle, UserHandle uh) throws Throwable {
        com.hush.utils.StubSession.fix(intent);
        traceStart("activity", activity, intent, i);
        return super.execStartActivity(ctx, b0, b1, activity, intent, i, bundle, uh);
    }

    /**
     * 记下「谁要拉起谁」，尤其是 HMS 内部那一串跳板。
     *
     * <p>登录链路是 设置页 → 引导页 → 登录页 → 跳板页 一层层 startActivity
     * 转过去的，请求参数全在 intent extra 里。只在 create 时看还不够：
     * 如果 A 传给 B 的参数就是空的，B 当然一进去就退。这里把发起方、
     * 目标组件、requestCode、extras 一起记下来，能直接看出参数是在哪一跳
     * 丢的。</p>
     */
    private static void traceStart(String from, Activity who, Intent it, int req) {
        com.hush.utils.StubLauncher.markStarted(who);
        try {
            if (it == null) return;
            android.content.ComponentName cn = it.getComponent();
            String pkg = cn != null ? cn.getPackageName() : it.getPackage();
            boolean hms = pkg != null && (pkg.startsWith("com.huawei") || pkg.startsWith("com.hihonor"));
            if (!hms) return;
            com.hush.utils.Diag.log("act", "start from=" + (who == null ? "ctx" : who.getClass().getName())
                    + " req=" + req + " cmp=" + cn + " act=" + it.getAction()
                    + " extras=" + describeExtras(it));
        } catch (Throwable ignored) {
        }
    }
}

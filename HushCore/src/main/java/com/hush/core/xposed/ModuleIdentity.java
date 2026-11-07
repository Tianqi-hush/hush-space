package com.hush.core.xposed;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.hush.utils.Diag;

/**
 * 模块「自我身份」层。
 *
 * <p>Xposed 模块在容器里跑的是<span>宿主进程</span>：它的代码来自
 * {@code /data/user/0/com.hush.space/files/data/app/com.sky.Star/base.apk}，
 * 但 {@code context.getPackageName()} / {@code getApplicationInfo()} 返回的
 * 都是光遇（com.netease.sky）的值。
 *
 * <p>绝大多数付费模块都有「防盗版」自检，第一步就是拿自己的包信息：
 *
 * <pre>
 *   PackageInfo pi = pm.getPackageInfo(getPackageName(), GET_SIGNATURES);
 *   if (!EXPECTED_SIG.equals(md5(pi.signatures[0]))) { 提示「抵制盗版」; return; }
 * </pre>
 *
 * <p>在容器里这条链路必然失败——查到的要么是光遇的签名，要么是
 * {@code NameNotFoundException}。失败后模块把所有功能入口直接 return，
 * 表现就是「菜单正常、点了没反应、还挂一条抵制盗版」。
 *
 * <p>这一层只回答「模块自己的事」：调用栈里出现模块包名前缀时，
 * 把包名 / ApplicationInfo / PackageInfo / APK 路径换回模块自己的真实值。
 * 游戏与宿主查自己时完全不受影响。
 */
public final class ModuleIdentity {

    private static final class Entry {
        final String pkg;
        final String apk;
        final String prefix;

        Entry(String pkg, String apk) {
            this.pkg = pkg;
            this.apk = apk;
            this.prefix = pkg + ".";
        }
    }

    private static final List<Entry> sEntries = new ArrayList<>();
    private static volatile boolean sInstalled;
    private static final java.util.concurrent.atomic.AtomicInteger sHits =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 身份窗口起点：启动期的自检必须命中，运行期则尽量少干预。 */
    private static volatile long sStartAt;
    private static final long WINDOW_MS = 3600000L;
    private static volatile boolean sWindowClosed;

    private ModuleIdentity() {
    }

    /** 在实例化模块入口<span>之前</span>登记，保证 HookInit 的静态初始化也能命中。 */
    public static synchronized void add(String modulePkg, String apkPath) {
        if (modulePkg == null || apkPath == null) {
            return;
        }
        for (Entry e : sEntries) {
            if (e.pkg.equals(modulePkg)) {
                return;
            }
        }
        sEntries.add(new Entry(modulePkg, apkPath));
        Diag.log("identity", "登记模块 " + modulePkg + " apk=" + apkPath);
    }

    public static synchronized void install() {
        if (sInstalled || sEntries.isEmpty()) {
            return;
        }
        sInstalled = true;
        sStartAt = android.os.SystemClock.elapsedRealtime();
        int n = 0;
        n += hookGetPackageInfoInt();
        n += hookGetPackageInfoFlags();
        n += hookContext("getPackageName", 0);
        n += hookContext("getPackageCodePath", 1);
        n += hookContext("getPackageResourcePath", 2);
        Diag.log("identity", "身份层已安装 " + n + " 个 hook 模块数=" + sEntries.size());
    }

    // ── 判定：这次调用是不是模块自己发的 ──────────────────────────────

    /**
     * 判定这次调用是不是模块自己发的。
     *
     * <p><b>只认直接调用方。</b>跳过 hook 框架和 Context 桥接帧后，看紧挨着的
     * 那一帧：是 {@code com.sky.Star.*} 才算模块调用，否则一律放行。
     *
     * <p>旧实现是往上翻 48 帧，只要栈里出现过模块类就算命中。模块的悬浮窗挂在
     * 游戏的视图树里，游戏自己在 measure/layout 里调 {@code getPackageName}
     * 时，栈下方必然有模块的 View 类 —— 于是游戏拿到的是 {@code com.sky.Star}，
     * 资源与主题解析随之错乱（登录页反复弹「请先勾选同意」就是这么来的）。
     */
    private static Entry caller() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        for (int i = 3; i < st.length && i < 32; i++) {
            String cn = st[i].getClassName();
            if (cn.startsWith("com.hush.core.xposed.")
                    || cn.startsWith("de.robv.android.xposed.")
                    || cn.startsWith("HushHooker_")
                    || cn.startsWith("java.lang.Thread")
                    || cn.startsWith("dalvik.")
                    || cn.startsWith("android.content.ContextWrapper")
                    || cn.startsWith("android.app.ContextImpl")) {
                continue;
            }
            for (Entry e : sEntries) {
                if (cn.startsWith(e.prefix)) {
                    return e;
                }
            }
            if (sSkipped.incrementAndGet() <= 8) {
                Diag.log("identity", "放行非模块调用 " + cn);
            }
            return null;
        }
        return null;
    }

    /** 被放行的「看似经过模块、其实不是模块发起」的调用方，用于确认误判已消除。 */
    private static final java.util.concurrent.atomic.AtomicInteger sSkipped =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 取栈有成本，而这些方法在游戏里可能被高频调用。
     * 给每个 hook 一个调用预算：模块的自检都发生在启动早期，预算内足够命中；
     * 超出后彻底退化为原方法，绝不拖慢游戏。
     */
    private static final java.util.concurrent.atomic.AtomicInteger sBudgetPkg =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger sBudgetPath =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger sBudgetAppInfo =
            new java.util.concurrent.atomic.AtomicInteger();

    private static boolean overBudget(int kind) {
        switch (kind) {
            case 0:
                return sBudgetPkg.incrementAndGet() > 20000;
            case 1:
            case 2:
                return sBudgetPath.incrementAndGet() > 4000;
            case 3:
                return sBudgetAppInfo.incrementAndGet() > 6000;
            default:
                return true;
        }
    }

    private static void noteHit(String what, String value) {
        if (sHits.incrementAndGet() <= 12) {
            Diag.log("identity", what + " -> " + value);
        }
    }

    // ── getPackageInfo ────────────────────────────────────────────────

    /** API 33 之前的主路径：getPackageInfo(String, int)。 */
    private static int hookGetPackageInfoInt() {
        try {
            Class<?> apm = Class.forName("android.app.ApplicationPackageManager");
            final Method target = apm.getDeclaredMethod("getPackageInfo", String.class, int.class);
            target.setAccessible(true);
            de.robv.android.xposed.XposedBridge.hookMethod(target,
                    new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Entry e = match(param.args);
                            if (e == null) {
                                return;
                            }
                            int flags = param.args.length > 1 && param.args[1] instanceof Integer
                                    ? (Integer) param.args[1] : 0;
                            PackageInfo pi = archive(e, flags);
                            if (pi != null) {
                                noteHit("getPackageInfo(" + e.pkg + ", " + flags + ")", "archive");
                                param.setResult(pi);
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            Diag.log("identity", "hook getPackageInfo(int) 失败 " + t);
            return 0;
        }
    }

    /** API 33+ 的 PackageInfoFlags 重载。 */
    private static int hookGetPackageInfoFlags() {
        try {
            Class<?> flagsCls = Class.forName("android.content.pm.PackageManager$PackageInfoFlags");
            Class<?> apm = Class.forName("android.app.ApplicationPackageManager");
            final Method target = apm.getDeclaredMethod("getPackageInfo", String.class, flagsCls);
            target.setAccessible(true);
            de.robv.android.xposed.XposedBridge.hookMethod(target,
                    new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Entry e = match(param.args);
                            if (e == null) {
                                return;
                            }
                            PackageInfo pi = archive(e,
                                    PackageManager.GET_SIGNATURES
                                            | PackageManager.GET_SIGNING_CERTIFICATES);
                            if (pi != null) {
                                noteHit("getPackageInfo(" + e.pkg + ", flags)", "archive");
                                param.setResult(pi);
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            // API 33 以下没有这个重载，正常
            return 0;
        }
    }

    private static Entry match(Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof String)) {
            return null;
        }
        String pkg = (String) args[0];
        for (Entry e : sEntries) {
            if (e.pkg.equals(pkg)) {
                return e;
            }
        }
        return null;
    }

    /** 从 APK 归档读真实包信息（含真实签名），容器 PMS 已支持 getPackageArchiveInfo。 */
    private static PackageInfo archive(Entry e, int flags) {
        try {
            int f = flags | PackageManager.GET_SIGNATURES;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                f |= PackageManager.GET_SIGNING_CERTIFICATES;
            }
            return com.hush.HushCore.getContext().getPackageManager()
                    .getPackageArchiveInfo(e.apk, f);
        } catch (Throwable t) {
            Diag.log("identity", "读归档失败 " + e.pkg + " " + t);
            return null;
        }
    }

    // ── Context 上的身份方法 ──────────────────────────────────────────

    /**
     * @param kind 0=getPackageName 1=getPackageCodePath 2=getPackageResourcePath
     *             3=getApplicationInfo
     */
    private static int hookContext(final String name, final int kind) {
        try {
            Class<?> impl = Class.forName("android.app.ContextImpl");
            Method target = impl.getDeclaredMethod(name);
            target.setAccessible(true);
            de.robv.android.xposed.XposedBridge.hookMethod(target,
                    new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (overBudget(kind)) {
                                return;
                            }
                            Entry e = caller();
                            if (e == null) {
                                return;
                            }
                            switch (kind) {
                                case 0:
                                    // getPackageName 是「改身份」里影响最大的一项：
                                    // 模块也可能拿它判断当前宿主。只在启动窗口内替换，
                                    // 覆盖 HookInit/GameHook 的自检，运行期恢复原值。
                                    if (android.os.SystemClock.elapsedRealtime() - sStartAt
                                            > WINDOW_MS) {
                                        if (!sWindowClosed) {
                                            sWindowClosed = true;
                                            Diag.log("identity", "getPackageName 身份窗口关闭");
                                        }
                                        return;
                                    }
                                    noteHit("getPackageName", e.pkg);
                                    param.setResult(e.pkg);
                                    break;
                                case 1:
                                case 2:
                                    noteHit(name, e.apk);
                                    param.setResult(e.apk);
                                    break;
                                case 3: {
                                    PackageInfo pi = archive(e, 0);
                                    ApplicationInfo ai = pi == null ? null : pi.applicationInfo;
                                    if (ai != null) {
                                        noteHit("getApplicationInfo", ai.sourceDir);
                                        param.setResult(ai);
                                    }
                                    break;
                                }
                                default:
                                    break;
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            Diag.log("identity", "hook ContextImpl#" + name + " 失败 " + t);
            return 0;
        }
    }
}

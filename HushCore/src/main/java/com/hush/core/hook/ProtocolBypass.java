package com.hush.core.hook;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;

import com.hush.app.BActivityThread;
import com.hush.utils.Diag;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * 绕过网易 SDK 那张永远画不出来的协议页。
 *
 * <p><b>已停用（1.6.36 起）。</b>{@code LsHooks} 里不再调用 {@link #start()}。
 * 1.6.32 修好 WebView 的 PackageInfo 之后，协议页已经能正常弹出、按钮可点，
 * 点「接受」就能往下走，不再需要替用户跳转；自动跳反而让用户以为「它自己进去了」。
 * 代码保留，需要时把 {@code LsHooks} 里注释掉的那一行恢复即可。
 *
 * 1.6.25 的零 hook 快照已经把结论钉死了：
 *
 * <pre>
 * ACT ...ProtocolLauncher decor=2808x1256
 * FLD mWebView   : UniWebView = null            ← WebView 从来没创建
 * FLD mContentView : FrameLayout = 0x0 vis=8    ← 内容容器是 GONE
 * FLD mLoadingView : View = null                ← 连 loading 都没有
 * </pre>
 *
 * 布局本身是好的（uni_p_root 占满 2808x1256），但三块内容
 * （uni_p_background / uni_p_content / uni_p_loaging）全部 GONE，
 * 20 秒内一次都没变，libunity.so 也从未出现在 maps 里。
 *
 * 也就是说 SDK 停在「等待协议检查结果」这一步，既没画东西也没往下跳。
 * 这一步依赖的是一次网络/初始化回调，容器里它不会来了。
 *
 * 所以这里做一件事：盯着这个 Activity，如果它空转超过 10 秒，
 * 就替它把后面那一步走掉 —— 启动真正的游戏入口 TGCBootActivity。
 *
 * 为什么目标是 TGCBootActivity 而不是 GameActivity_Netease：
 * manifest 里 TGCBootActivity 是 exported=1、screenOrientation=6 的引导页，
 * Unity 的资源校验/热更都走它；直接进 GameActivity 会跳过这些前置步骤。
 */
public final class ProtocolBypass {

    private static final String GUEST_PKG = "com.netease.sky";

    /** 网易 SDK 的协议页。它画不出内容，是我们卡住的地方。 */
    private static final String P_LAUNCHER =
            "com.netease.ntunisdk.external.protocol.ProtocolLauncher";

    /** 真正的游戏入口（manifest 里 exported=1、so=6 的引导页）。 */
    private static final String T_BOOT = "com.tgc.sky.TGCBootActivity";

    /**
     * 空转多久后才动手。
     *
     * 1.6.33 的实测：WebView 在 6.2 秒就 loadUrl 完成（prog=10、ch=0），
     * 之后到 25 秒一次都没变过，且 about:blank 的 JS 心跳同样 MISSING ——
     * 渲染进程在容器里根本没有工作，等多久都不会变好。
     *
     * 所以压到 12 秒：留 6 秒给 WebView 创建和首次导航，再留 6 秒确认它
     * 确实停在 prog=10。到点还没内容就说明不会再有了。
     *
     * 如果 {@link ProtocolText} 已经把官方正文用原生 View 铺出来了，
     * 这里不会替用户跳 —— 让他自己读、自己点接受。
     */
    private static final long IDLE_MS = 12_000L;

    private static boolean sStarted = false;

    private ProtocolBypass() {
    }

    public static synchronized void start() {
        if (sStarted) return;
        sStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "HushProtoBypass");
        t.setDaemon(true);
        t.start();
        Diag.log("bypass", "armed idle=" + IDLE_MS + "ms -> " + T_BOOT);
    }

    private static void loop() {
        long firstSeen = 0L;
        boolean fired = false;

        while (true) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                return;
            }
            if (fired) continue;

            Object launcher = findLauncher();
            if (launcher == null) {
                firstSeen = 0L;
                continue;
            }
            if (firstSeen == 0L) {
                firstSeen = android.os.SystemClock.uptimeMillis();
                Diag.log("bypass", "seen " + P_LAUNCHER + " webview=" + (
                        fieldOf(launcher, "mWebView") == null ? "null" : "obj"));
                continue;
            }

            long idle = android.os.SystemClock.uptimeMillis() - firstSeen;
            if (idle < IDLE_MS) {
                // 趁等的这段时间把官方正文拉下来。WebView 一有 URL 就动手，
                // 拉的是同一个地址，内容因此和 SDK 想显示的一致。
                ProtocolText.probe(launcher);
                continue;
            }

            // 正文已经用原生 View 铺出来了：不替用户做决定，让他自己点。
            if (ProtocolText.shown()) {
                Diag.log("bypass", "protocol text shown by native view, stay for user");
                return;
            }

            // 只有「页面确实是空的」才绕过。如果 SDK 把 WebView 建出来了，
            // 说明协议页是正常的，用户自己点就行，不能替用户做决定。
            Object wv = fieldOf(launcher, "mWebView");
            Object content = fieldOf(launcher, "mContentView");
            boolean contentHidden = !(content instanceof View)
                    || ((View) content).getVisibility() != View.VISIBLE;

            Diag.log("bypass", "idle=" + idle + "ms webview=" + (wv == null ? "null" : "obj")
                    + " contentHidden=" + contentHidden);

            if (wv != null && !contentHidden) {
                Diag.log("bypass", "protocol page looks alive, stay out");
                fired = true;
                continue;
            }

            fired = true;
            fire(launcher);
            probeAfterFire();
            return;
        }
    }

    /**
     * 触发之后 4 秒回看一眼：协议页有没有关掉、顶层换成了谁。
     *
     * 1.6.29 的日志只到 "startActivity ok" 就断了，后面进没进 TGCBootActivity
     * 全靠用户口述。这一步把结果写进 diag，不用再猜。
     */
    private static void probeAfterFire() {
        try {
            Thread.sleep(4000L);
        } catch (InterruptedException e) {
            return;
        }
        try {
            Object l = findLauncher();
            Diag.log("bypass", "post4s launcher=" + (l == null ? "gone" : "alive")
                    + " acts=" + activitySnapshot());
        } catch (Throwable t) {
            Diag.log("bypass", "post4s failed " + t);
        }
    }

    /** 当前还活着的 Activity 类名（最多 4 个），用于确认顶层换没换。 */
    private static String activitySnapshot() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cur = at.getDeclaredMethod("currentActivityThread");
            cur.setAccessible(true);
            Object thread = cur.invoke(null);
            if (thread == null) return "none";

            Field f = null;
            Class<?> c = thread.getClass();
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField("mActivities");
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) return "nofld";
            f.setAccessible(true);
            Object map = f.get(thread);
            if (!(map instanceof Map)) return "nomap";

            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Object rec : ((Map<?, ?>) map).values()) {
                Object act = fieldOf(rec, "activity", "mActivity");
                if (!(act instanceof Activity)) continue;
                if (n++ > 0) sb.append(',');
                String name = act.getClass().getName();
                int dot = name.lastIndexOf('.');
                sb.append(dot > 0 ? name.substring(dot + 1) : name);
                if (n >= 4) break;
            }
            return n == 0 ? "none" : sb.toString();
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    private static void fire(Object launcher) {
        try {
            Context app = BActivityThread.getApplication();
            if (app == null) {
                Diag.log("bypass", "no application context");
                return;
            }
            Intent i = new Intent();
            i.setComponent(new ComponentName(GUEST_PKG, T_BOOT));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // 1.6.33 的反馈：跳得太安静，用户以为「游戏自己进去了」。
            // 给一句提示，把这件事变成可感知的。
            toast(app, "协议页无法渲染，已跳过");

            app.startActivity(i);
            Diag.log("bypass", "startActivity " + T_BOOT + " ok");
        } catch (Throwable t) {
            Diag.log("bypass", "startActivity failed " + t);
            return;
        }

        // 把空转的协议页收掉，否则它一直占着 task 的顶层。
        try {
            if (launcher instanceof Activity) {
                ((Activity) launcher).finish();
                Diag.log("bypass", "launcher finish ok");
            }
        } catch (Throwable t) {
            Diag.log("bypass", "finish failed " + t);
        }
    }

    /** 提示必须在主线程弹；拿不到 context 就静默放弃，不能因此中断推进。 */
    private static void toast(final Context ctx, final String msg) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.widget.Toast.makeText(ctx, msg,
                                android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 在 mActivities 里找还活着的 ProtocolLauncher 实例。 */
    private static Object findLauncher() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cur = at.getDeclaredMethod("currentActivityThread");
            cur.setAccessible(true);
            Object thread = cur.invoke(null);
            if (thread == null) return null;

            Field f = null;
            Class<?> c = thread.getClass();
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField("mActivities");
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) return null;
            f.setAccessible(true);
            Object map = f.get(thread);
            if (!(map instanceof Map)) return null;

            for (Object rec : ((Map<?, ?>) map).values()) {
                Object act = fieldOf(rec, "activity", "mActivity");
                if (act instanceof Activity
                        && act.getClass().getName().equals(P_LAUNCHER)) {
                    return act;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object fieldOf(Object o, String... names) {
        if (o == null) return null;
        Class<?> c = o.getClass();
        while (c != null) {
            for (String n : names) {
                try {
                    Field f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (NoSuchFieldException ignored) {
                } catch (Throwable ignored) {
                    return null;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }
}

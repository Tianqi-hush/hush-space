package com.hush.core.hook;

import android.app.Activity;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

import com.hush.core.VNative;
import com.hush.utils.Diag;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * 旋转/窗口变化的观测层。
 *
 * 为什么需要它：光遇进游戏后切横屏会黑屏，但 diag.log 里旋转那几个事务只有
 * {@code TopResumedActivityChangeItem} 和 {@code WindowStateResizeItem}，
 * **没有 {@code ActivityConfigurationChangeItem}**。单看这个只能说明「系统没发
 * 配置变化」，但发不出的原因有三种完全不同的可能：
 *
 * <ul>
 *   <li>Activity 的 configChanges 覆盖了，本来就不该发 —— 该由 App 自己重绘；</li>
 *   <li>Activity 被重建了，走的是 Destroy+Launch 而不是 config change；</li>
 *   <li>窗口只是 letterbox resize，Configuration 确实没变。</li>
 * </ul>
 *
 * 这三者的处置手段完全不同（手动 dispatch、补窗口尺寸、还是改 ActivityInfo），
 * 所以必须把「Activity 到底收到了什么」原样打出来，而不是继续靠猜。
 *
 * 纪律：只在壳白名单（g_hookGate）下安装；普通 App 一条 hook 都不装。
 */
public final class RotateProbe {

    private static boolean sInstalled = false;

    /** 已经 hook 过的「类#方法」组合，避免同一方法重复 hook。 */
    private static final Set<String> sHooked = new HashSet<>();

    private RotateProbe() {
    }

    public static synchronized void install() {
        if (sInstalled) return;
        sInstalled = true;

        // 只 hook onConfigurationChanged 和 SurfaceView 的三个回调。
        //
        // 1.6.20 里同时 hook 了 onResume/onStart/onPause/onStop/onDestroy，
        // 结果是 ProtocolLauncher 起来后彻底静默、连第二个 LaunchActivityItem
        // 都没有 —— 而易盾会检查关键方法的 ART entrypoint，被改过的生命周期
        // 方法足以让它走安全分支。这一版把这些全部撤掉：它们只在启动链路上
        // 触发，收益小、代价大。
        //
        // 留下的这几个恰恰是「只在旋转/建 Surface 那一刻才触发」的，
        // 对正常启动流程零影响。
        int ok = 0;
        ok += hook(Activity.class, "onConfigurationChanged", Configuration.class);
        ok += hook(android.view.SurfaceView.class, "surfaceCreated", android.view.SurfaceHolder.class);
        ok += hook(android.view.SurfaceView.class, "surfaceChanged", android.view.SurfaceHolder.class,
                int.class, int.class, int.class);
        ok += hook(android.view.SurfaceView.class, "surfaceDestroyed", android.view.SurfaceHolder.class);

        Diag.log("rot", "probe installed " + ok + "/4");
    }

    private static int hook(Class<?> cls, String name, Class<?>... params) {
        String key = cls.getName() + "#" + name;
        if (sHooked.contains(key)) return 0;
        try {
            Method target = cls.getDeclaredMethod(name, params);
            target.setAccessible(true);
            Hooker hooker = new Hooker(name);
            Method cb = Hooker.class.getDeclaredMethod("callback", Object[].class);
            Method backup = VNative.hookMethod(target, hooker, cb);
            if (backup == null) {
                Diag.log("rot", "hook miss " + key);
                return 0;
            }
            hooker.backup = backup;
            sHooked.add(key);
            return 1;
        } catch (NoSuchMethodException e) {
            // ROM 没有这个方法，正常，不当错误。
            return 0;
        } catch (Throwable t) {
            Diag.log("rot", "hook fail " + key + " " + t);
            return 0;
        }
    }

    public static final class Hooker {
        public Method backup;
        final String name;

        Hooker(String name) {
            this.name = name;
        }

        public Object callback(Object[] args) {
            try {
                report(name, args);
            } catch (Throwable ignored) {
            }
            try {
                return HookInvoke.call(backup, args);
            } catch (Throwable t) {
                // 生命周期方法不能静默吞掉，否则 Activity 状态机就断了。
                throw new RuntimeException(t);
            }
        }
    }

    private static void report(String name, Object[] args) {
        if (args == null || args.length == 0) return;

        // SurfaceView 的三个回调：receiver 是 SurfaceView，参数是 Holder / 尺寸。
        if (!(args[0] instanceof Activity)) {
            if (args[0] instanceof android.view.SurfaceView) {
                reportSurface(name, args);
            }
            return;
        }
        Activity a = (Activity) args[0];

        StringBuilder sb = new StringBuilder(160);
        sb.append(name).append(" act=").append(a.getClass().getName());

        for (int i = 1; i < args.length; ++i) {
            Object v = args[i];
            if (v instanceof Configuration) {
                Configuration c = (Configuration) v;
                sb.append(" cfg[o=").append(c.orientation)
                        .append(" ").append(c.screenWidthDp).append('x').append(c.screenHeightDp)
                        .append(" sw=").append(c.smallestScreenWidthDp)
                        .append(" ui=").append(c.uiMode)
                        .append(']');
            } else {
                sb.append(' ').append(v);
            }
        }

        try {
            DisplayMetrics dm = new DisplayMetrics();
            a.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            sb.append(" real=").append(dm.widthPixels).append('x').append(dm.heightPixels)
                    .append(" rot=").append(a.getWindowManager().getDefaultDisplay().getRotation());
        } catch (Throwable ignored) {
        }

        try {
            sb.append(" reqO=").append(a.getRequestedOrientation())
                    .append(" changing=0x").append(Integer.toHexString(a.getChangingConfigurations()));
        } catch (Throwable ignored) {
        }

        try {
            android.view.View d = a.getWindow() == null ? null : a.getWindow().getDecorView();
            if (d != null) {
                sb.append(" decor=").append(d.getWidth()).append('x').append(d.getHeight());
            }
        } catch (Throwable ignored) {
        }

        // 容器给系统的是「谁的 ActivityInfo」—— 这决定了系统判定要不要发配置变化。
        // 如果是宿主 StubActivity 的，configChanges 覆盖一切、screenOrientation 也是宿主的，
        // 那就解释了为什么旋转时一条 ActivityConfigurationChangeItem 都没有。
        try {
            java.lang.reflect.Field f = Activity.class.getDeclaredField("mActivityInfo");
            f.setAccessible(true);
            android.content.pm.ActivityInfo ai = (android.content.pm.ActivityInfo) f.get(a);
            if (ai != null) {
                sb.append(" ai[cc=0x").append(Integer.toHexString(ai.configChanges))
                        .append(" so=").append(ai.screenOrientation)
                        .append(" rsz=").append(intField(ai, "resizeMode"))
                        .append(" cls=").append(ai.name)
                        .append(" pkg=").append(ai.packageName)
                        .append(']');
            }
        } catch (Throwable ignored) {
        }

        Diag.log("rot", sb.toString());
    }

    private static int intField(Object o, String name) {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getInt(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static void reportSurface(String name, Object[] args) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(name).append(" view=").append(args[0].getClass().getName());
        for (int i = 1; i < args.length; ++i) {
            Object v = args[i];
            if (v instanceof Integer) {
                sb.append(' ').append(v);
            }
        }
        try {
            android.view.SurfaceView sv = (android.view.SurfaceView) args[0];
            android.view.SurfaceHolder h = sv.getHolder();
            if (h != null && h.getSurface() != null) {
                android.graphics.Rect r = h.getSurfaceFrame();
                sb.append(" frame=").append(r == null ? "null" : (r.width() + "x" + r.height()))
                        .append(" valid=").append(h.getSurface().isValid());
            }
        } catch (Throwable ignored) {
        }
        Diag.log("surf", sb.toString());
    }
}

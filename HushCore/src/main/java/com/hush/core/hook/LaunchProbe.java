package com.hush.core.hook;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.hush.core.VNative;
import com.hush.utils.Diag;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * 组件跳转观测层。
 *
 * 为什么需要它：光遇的入口是 {@code com.netease.ntunisdk.external.protocol.ProtocolLauncher}
 * —— 网易 SDK 的一个无 UI 跳转页，正常行为是 onCreate 里解析 Intent，
 * 然后 startActivity 到真正的游戏 Activity 并 finish。
 *
 * 实际日志里它 onStart/onResume/拿到焦点/窗口 resize 都走了，之后就彻底静默：
 * 没有第二个 LaunchActivityItem、没有 SurfaceView、没有 EGL。主线程停在
 * nativePollOnce（空闲），说明**不是卡死，而是它主动什么都没做**。
 *
 * 「什么都没做」的可能只有两种，且处置完全不同：
 *
 * <ul>
 *   <li>它调了 startActivity，但容器侧把 Intent 解析/校验拒了 —— 看这里有没有调用、有没有抛异常；</li>
 *   <li>它压根没调，因为前面某次 resolveActivity/queryIntentActivities 返回了空 ——
 *       看 PackageManager 那几个查询的返回。</li>
 * </ul>
 *
 * 所以这里把 startActivity 全部重载 + PackageManager 的解析入口一起打出来。
 * 只观测、不改行为；任何异常原样重抛。
 */
public final class LaunchProbe {

    private static boolean sInstalled = false;
    private static final Set<String> sHooked = new HashSet<>();

    private LaunchProbe() {
    }

    public static synchronized void install() {
        if (sInstalled) return;
        sInstalled = true;

        int ok = 0;
        ok += hook(Activity.class, "startActivity", Intent.class);
        ok += hook(Activity.class, "startActivity", Intent.class, Bundle.class);
        ok += hook(Activity.class, "startActivityForResult", Intent.class, int.class);
        ok += hook(Activity.class, "startActivityForResult", Intent.class, int.class, Bundle.class);

        // ApplicationPackageManager 是 hidden 类，编译期没有，只能反射拿。
        // 容器自己的 PackageManagerCompat 走的是另一条路，但 guest 里大部分代码
        // 拿到的仍然是这个系统实现，解析失败就发生在这里。
        try {
            Class<?> apm = Class.forName("android.app.ApplicationPackageManager");
            ok += hook(apm, "resolveActivity", Intent.class, int.class);
            ok += hook(apm, "queryIntentActivities", Intent.class, int.class);
            ok += hook(apm, "getLaunchIntentForPackage", String.class);
        } catch (Throwable t) {
            Diag.log("launch", "apm unavailable " + t);
        }

        Diag.log("launch", "probe installed " + ok + "/7");
    }

    private static int hook(Class<?> cls, String name, Class<?>... params) {
        String key = cls.getName() + "#" + name + "/" + params.length;
        if (sHooked.contains(key)) return 0;
        try {
            Method target = cls.getDeclaredMethod(name, params);
            target.setAccessible(true);
            Hooker hooker = new Hooker(name);
            Method cb = Hooker.class.getDeclaredMethod("callback", Object[].class);
            Method backup = VNative.hookMethod(target, hooker, cb);
            if (backup == null) {
                Diag.log("launch", "hook miss " + key);
                return 0;
            }
            hooker.backup = backup;
            sHooked.add(key);
            return 1;
        } catch (NoSuchMethodException e) {
            return 0;
        } catch (Throwable t) {
            Diag.log("launch", "hook fail " + key + " " + t);
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
            Object r = null;
            Throwable err = null;
            try {
                r = HookInvoke.call(backup, args);
            } catch (Throwable t) {
                err = t;
            }
            try {
                report(name, args, r, err);
            } catch (Throwable ignored) {
            }
            if (err != null) {
                throw err instanceof RuntimeException ? (RuntimeException) err : new RuntimeException(err);
            }
            return r;
        }
    }

    private static void report(String name, Object[] args, Object ret, Throwable err) {
        if (args == null || args.length == 0) return;
        Object recv = args[0];

        StringBuilder sb = new StringBuilder(192);
        sb.append(name).append(" by=")
                .append(recv == null ? "null" : recv.getClass().getName());

        if (args.length > 1 && args[1] instanceof Intent) {
            appendIntent(sb, (Intent) args[1]);
        } else if (args.length > 1 && args[1] instanceof String) {
            sb.append(" pkg=").append(args[1]);
        }

        if (name.startsWith("resolve") || name.startsWith("query") || name.startsWith("getLaunch")) {
            sb.append(" ret=").append(describe(ret));
        }

        if (err != null) {
            sb.append(" EX=").append(err.getClass().getSimpleName()).append(':').append(err.getMessage());
        }

        Diag.log("launch", sb.toString());
    }

    private static String describe(Object ret) {
        if (ret == null) return "null";
        if (ret instanceof java.util.List) {
            int n = ((java.util.List<?>) ret).size();
            StringBuilder sb = new StringBuilder("list(").append(n).append(')');
            java.util.List<?> l = (java.util.List<?>) ret;
            for (int i = 0; i < n && i < 4; ++i) {
                Object o = l.get(i);
                sb.append(' ').append(o == null ? "null" : o.getClass().getSimpleName());
                try {
                    java.lang.reflect.Method m = o.getClass().getMethod("getComponentName");
                    sb.append('=').append(m.invoke(o));
                } catch (Throwable ignored) {
                }
            }
            return sb.toString();
        }
        try {
            java.lang.reflect.Method m = ret.getClass().getMethod("getComponentName");
            Object cn = m.invoke(ret);
            if (cn != null) return ret.getClass().getSimpleName() + "=" + cn;
        } catch (Throwable ignored) {
        }
        return ret.getClass().getSimpleName();
    }

    private static void appendIntent(StringBuilder sb, Intent it) {
        if (it == null) {
            sb.append(" intent=null");
            return;
        }
        sb.append(" intent{");
        if (it.getComponent() != null) sb.append("cmp=").append(it.getComponent().flattenToShortString());
        else sb.append("cmp=null");
        if (it.getAction() != null) sb.append(" act=").append(it.getAction());
        if (it.getDataString() != null) sb.append(" data=").append(it.getDataString());
        sb.append(" flg=0x").append(Integer.toHexString(it.getFlags()));
        Bundle b = it.getExtras();
        if (b != null) {
            try {
                sb.append(" ext=").append(b.keySet());
            } catch (Throwable t) {
                sb.append(" ext=<unreadable>");
            }
        } else {
            sb.append(" ext=null");
        }
        sb.append('}');
    }
}

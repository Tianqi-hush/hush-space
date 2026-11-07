package com.hush.core.hook;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import com.hush.core.VNative;
import com.hush.utils.Diag;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * WebView 观测层。
 *
 * 日志已经证明：ProtocolLauncher 不是「算完就跳」，它在 onCreate 里 new 了一个 WebView
 * （栈里 {@code WebView.<init> -> WebViewChromiumFactoryProvider.<init> -> browserStartupComplete}），
 * 之后主线程进入 nativePollOnce、Chrome_IOThread 在跑网络，然后**再没有任何 startActivity**。
 *
 * 也就是说：它要靠网页加载完的回调来决定下一步。网页不出结果，它就永远停在那，
 * 屏幕上是一片横屏黑——这跟旋转无关，旋转只是让这块空白变成横向的。
 *
 * 容器里 WebView 出问题很常见，且三种成因表现完全一样（都是黑屏）：
 *
 * <ul>
 *   <li>loadUrl 根本没被调用 —— 走到了别的分支；</li>
 *   <li>loadUrl 调了，但页面加载失败（证书/DNS/MixedContent） —— 回调永远不来；</li>
 *   <li>页面加载成功，但 WebView 尺寸为 0 或没 attach —— 有内容也画不出来。</li>
 * </ul>
 *
 * 这里打印 loadUrl 的 URL，并在 4 秒后 dump 一次尺寸/进度/可见性/父链，
 * 一次就能把三种区分开。
 */
public final class WebProbe {

    private static boolean sInstalled = false;
    private static final Set<String> sHooked = new HashSet<>();

    private WebProbe() {
    }

    public static synchronized void install() {
        if (sInstalled) return;
        sInstalled = true;

        int ok = 0;
        ok += hook(WebView.class, "loadUrl", String.class);
        ok += hook(WebView.class, "loadUrl", String.class, Map.class);
        ok += hook(WebView.class, "loadDataWithBaseURL", String.class, String.class,
                String.class, String.class, String.class);
        ok += hook(WebView.class, "loadData", String.class, String.class, String.class);

        Diag.log("web", "probe installed " + ok + "/4");
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
                Diag.log("web", "hook miss " + key);
                return 0;
            }
            hooker.backup = backup;
            sHooked.add(key);
            return 1;
        } catch (NoSuchMethodException e) {
            return 0;
        } catch (Throwable t) {
            Diag.log("web", "hook fail " + key + " " + t);
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
                report(name, args, err);
            } catch (Throwable ignored) {
            }
            if (err != null) {
                throw err instanceof RuntimeException ? (RuntimeException) err : new RuntimeException(err);
            }
            return r;
        }
    }

    private static void report(String name, Object[] args, Throwable err) {
        if (args == null || args.length < 2 || !(args[0] instanceof WebView)) return;
        final WebView wv = (WebView) args[0];
        String url = null;
        for (int i = 1; i < args.length; ++i) {
            if (args[i] instanceof String && ((String) args[i]).length() > 8) {
                url = (String) args[i];
                break;
            }
        }

        StringBuilder sb = new StringBuilder(160);
        sb.append(name).append(" url=").append(shorten(url));
        if (err != null) {
            sb.append(" EX=").append(err.getClass().getSimpleName()).append(':').append(err.getMessage());
        }
        Diag.log("web", sb.toString());

        // 4 秒后看结果：加载完没有、有没有尺寸、挂在窗口上没有。
        final String u = url;
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    dump(wv, u);
                }
            }, 4000);
        } catch (Throwable ignored) {
        }
    }

    private static void dump(WebView wv, String reqUrl) {
        StringBuilder sb = new StringBuilder(224);
        try {
            sb.append("state req=").append(shorten(reqUrl));
            sb.append(" url=").append(shorten(wv.getUrl()));
            sb.append(" prog=").append(wv.getProgress());
            sb.append(" size=").append(wv.getWidth()).append('x').append(wv.getHeight());
            sb.append(" vis=").append(visibility(wv.getVisibility()));
            sb.append(" attached=").append(wv.isAttachedToWindow() ? 1 : 0);
            sb.append(" shown=").append(wv.isShown() ? 1 : 0);
        } catch (Throwable t) {
            sb.append(" state err=").append(t);
        }

        // 父链：确认 WebView 到底有没有被塞进 Activity 的 contentView。
        try {
            StringBuilder chain = new StringBuilder();
            Object p = wv.getParent();
            int depth = 0;
            while (p != null && depth < 5) {
                chain.append(p.getClass().getSimpleName()).append('<');
                try {
                    java.lang.reflect.Method m = p.getClass().getMethod("getParent");
                    p = m.invoke(p);
                } catch (Throwable t) {
                    p = null;
                }
                ++depth;
            }
            sb.append(" parents=").append(chain.length() == 0 ? "none" : chain.toString());
        } catch (Throwable ignored) {
        }

        Diag.log("web", sb.toString());
    }

    private static String visibility(int v) {
        switch (v) {
            case 0: return "VISIBLE";
            case 4: return "INVISIBLE";
            case 8: return "GONE";
            default: return String.valueOf(v);
        }
    }

    private static String shorten(String s) {
        if (s == null) return "null";
        return s.length() <= 110 ? s : s.substring(0, 110) + "…";
    }
}

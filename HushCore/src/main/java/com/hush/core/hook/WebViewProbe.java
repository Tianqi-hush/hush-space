package com.hush.core.hook;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import com.hush.app.BActivityThread;
import com.hush.utils.Diag;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebView 可用性探针（只读，零 hook）。
 *
 * 1.6.31 的 diag 第一次抓到协议页真正的死因：
 *
 * <pre>
 * ServiceConfigurationError: ... PackageInfo.versionCode ... null
 *   at WV.s02.&lt;init&gt; / AwBrowserProcess.b / WebViewChromium.init
 *   at android.webkit.WebView.&lt;init&gt; / UniWebView.&lt;init&gt;
 *   at ProtocolLauncher.initWebView / showProtocol
 * </pre>
 *
 * 也就是说 UniSDK 确实去建 WebView 了（之前 8 次快照里 mWebView 一直是 null，
 * 只是因为拿协议配置的 AsyncTask 要 12 秒才回来），建的时候 Chromium 拿到
 * 一个 null 的 PackageInfo，读 versionCode 直接 NPE，异常穿透主线程，
 * 再被易盾的 handler 递归两万层埋掉。
 *
 * 这个探针不参与修复，只负责在「协议页创建之前」把三方口径同时写进日志：
 *
 * <pre>
 * wv: #0 provider=com.google.android.webview loaded=null static=null
 *     gwebview=ok(787104601) awebview=nfn chrome=nfn self=ok(1)
 * </pre>
 *
 * 有了这一行，就能分清 null 到底出自哪一层：
 * 框架静态缓存（loaded/static）、还是 guest PM 查不到（ok/nfn）。
 */
public final class WebViewProbe {

    private static final String[] CANDIDATES = {
            "com.google.android.webview",
            "com.android.webview",
            "com.android.chrome",
    };

    /**
     * 采样点。1.6.32 的日志显示协议 WebView 在 6.2s 就已经 loadUrl，
     * 11.2s 时变成 url=null，所以 9s 和 14s 这两枪是唯一能区分
     * 「网络慢」「渲染进程没起来」「SDK 自己把页面销毁重建」的时刻。
     */
    private static final long[] DELAYS = {3_000L, 9_000L, 14_000L, 22_000L, 30_000L};

    private static boolean sStarted = false;

    private WebViewProbe() {
    }

    public static synchronized void start(final String guestPkg) {
        if (sStarted) return;
        sStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long base = android.os.SystemClock.uptimeMillis();
                for (int i = 0; i < DELAYS.length; i++) {
                    long wait = DELAYS[i] - (android.os.SystemClock.uptimeMillis() - base);
                    if (wait > 0) {
                        try {
                            Thread.sleep(wait);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    try {
                        probe(guestPkg, i);
                    } catch (Throwable t2) {
                        Diag.log("wv", "#" + i + " probe failed " + t2);
                    }
                }
            }
        }, "HushWebViewProbe");
        t.setDaemon(true);
        t.start();
        Diag.log("wv", "probe armed x" + DELAYS.length);
    }

    private static void probe(String guestPkg, int round) {
        Context ctx = null;
        try {
            ctx = BActivityThread.getApplication();
        } catch (Throwable ignored) {
        }
        if (ctx == null) {
            Diag.log("wv", "#" + round + " no-context");
            render(round);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append('#').append(round);
        // 框架侧口径：provider 包名、getLoadedPackageInfo()、静态字段 sPackageInfo
        sb.append(" provider=").append(String.valueOf(fw("getWebViewPackageName")));
        sb.append(" loaded=").append(describe(fw("getLoadedPackageInfo")));
        sb.append(" static=").append(describe(staticField()));

        PackageManager pm = ctx.getPackageManager();
        for (String p : CANDIDATES) {
            sb.append(' ').append(shortName(p)).append('=').append(query(pm, p, 0));
        }
        // 最后一项带上 Chromium 实际用的那组 flags，专门验「带 flags 会不会查不到」
        sb.append(" flags=").append(query(pm, "com.google.android.webview",
                PackageManager.GET_META_DATA | PackageManager.GET_SIGNATURES | 0x2000));
        sb.append(" self=").append(query(pm, guestPkg, 0));

        Diag.log("wv", sb.toString());

        render(round);
    }

    /**
     * 渲染心跳。
     *
     * 1.6.32 的快照里，协议 WebView 的 progress 从 6.2s 到 8.7s 一直是 10，
     * 11.2s 之后干脆变成 url=null/prog=0；连另一个 about:blank 的 WebView
     * 也卡在 prog=10。about:blank 不需要网络，如果它都到不了 100，
     * 那就只能是 renderer 没有提交过首帧。
     *
     * postVisualStateCallback 是判断这件事最干净的公开 API：
     * 它要求 WebView 完成一次视觉状态提交才回调，renderer 不工作就永远不回调。
     * 这里只读、只观察，不做任何修改。
     */
    private static void render(final int round) {
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override
            public void run() {
                List<WebView> list = collect();
                if (list.isEmpty()) {
                    Diag.log("wvr", "#" + round + " none");
                    return;
                }
                for (int i = 0; i < list.size(); ++i) {
                    final WebView wv = list.get(i);
                    String url;
                    int prog, ch, vis, w, ht;
                    boolean att, hw;
                    try {
                        url = wv.getUrl();
                        prog = wv.getProgress();
                        ch = wv.getContentHeight();
                        vis = wv.getVisibility();
                        w = wv.getWidth();
                        ht = wv.getHeight();
                        att = wv.isAttachedToWindow();
                        hw = wv.isHardwareAccelerated();
                    } catch (Throwable t) {
                        Diag.log("wvr", "#" + round + " [" + i + "] read-err " + t);
                        continue;
                    }
                    Diag.log("wvr", "#" + round + " [" + i + "]"
                            + " url=" + (url == null ? "null"
                                    : (url.length() > 70 ? url.substring(0, 70) + "…" : url))
                            + " prog=" + prog
                            + " ch=" + ch
                            + " " + w + "x" + ht
                            + " vis=" + vis
                            + " att=" + (att ? 1 : 0)
                            + " hw=" + (hw ? 1 : 0)
                            + " lay=" + wv.getLayerType()
                            + " js=" + jsEnabled(wv));

                    // JS 心跳：不依赖可见性。只要 renderer 活着、文档存在，
                    // evaluateJavascript 就一定会回调（返回 "2"）。
                    // 这是判断「renderer 到底有没有在跑」最便宜的探针 ——
                    // 比 postVisualStateCallback 更可靠，因为后者要求 View 可见。
                    final int idx = i;
                    if (url == null) {
                        Diag.log("wvr", "#" + round + " [" + idx + "] js skip (no document)");
                    } else {
                        final AtomicBoolean js = new AtomicBoolean(false);
                        try {
                            wv.evaluateJavascript("1+1", new android.webkit.ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String value) {
                                    js.set(true);
                                    Diag.log("wvr", "#" + round + " [" + idx + "] js FIRED " + value);
                                }
                            });
                        } catch (Throwable t) {
                            Diag.log("wvr", "#" + round + " [" + idx + "] js call-err " + t);
                        }
                        h.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!js.get()) {
                                    Diag.log("wvr", "#" + round + " [" + idx + "] js MISSING(5s)");
                                }
                            }
                        }, 5_000L);
                    }

                    // 只有可见且已 attach 的 WebView 才谈得上「提交视觉状态」，
                    // 其余情况一律标 skip，避免把「没显示」误判成「渲染不了」。
                    if (!att || vis != View.VISIBLE) {
                        Diag.log("wvr", "#" + round + " [" + i + "] vsync skip (not visible)");
                        continue;
                    }
                    final AtomicBoolean got = new AtomicBoolean(false);
                    try {
                        wv.postVisualStateCallback(round * 100L + i,
                                new WebView.VisualStateCallback() {
                                    @Override
                                    public void onComplete(long requestId) {
                                        got.set(true);
                                        Diag.log("wvr", "#" + round + " [" + idx
                                                + "] vsync FIRED id=" + requestId);
                                    }
                                });
                    } catch (Throwable t) {
                        Diag.log("wvr", "#" + round + " [" + idx + "] vsync call-err " + t);
                        continue;
                    }
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!got.get()) {
                                Diag.log("wvr", "#" + round + " [" + idx + "] vsync MISSING(5s)");
                            }
                        }
                    }, 5_000L);
                }
            }
        });
    }

    /** 从 WindowManagerGlobal.mViews 里递归收集所有 WebView（主线程调用）。 */
    private static List<WebView> collect() {
        List<WebView> out = new ArrayList<WebView>();
        try {
            Class<?> cls = Class.forName("android.view.WindowManagerGlobal");
            Method get = cls.getDeclaredMethod("getInstance");
            get.setAccessible(true);
            Object inst = get.invoke(null);
            if (inst == null) return out;
            Field fv = cls.getDeclaredField("mViews");
            fv.setAccessible(true);
            Object views = fv.get(inst);
            if (!(views instanceof List)) return out;
            for (Object o : (List<?>) views) {
                if (o instanceof View) collectIn((View) o, out);
            }
        } catch (Throwable t) {
            Diag.log("wvr", "collect err " + t);
        }
        return out;
    }

    private static void collectIn(View v, List<WebView> out) {
        if (v == null) return;
        if (v instanceof WebView) {
            out.add((WebView) v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); ++i) collectIn(g.getChildAt(i), out);
        }
    }

    /** evaluateJavascript 是否被 WebSettings 关掉，决定 js 心跳失败怎么解读。 */
    private static String jsEnabled(WebView wv) {
        try {
            return wv.getSettings().getJavaScriptEnabled() ? "1" : "0";
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 反射调 android.webkit.WebViewFactory 的公开（隐藏）静态方法。 */
    private static Object fw(String method) {
        try {
            Method m = Class.forName("android.webkit.WebViewFactory")
                    .getDeclaredMethod(method);
            m.setAccessible(true);
            return m.invoke(null);
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    /** 反射读 WebViewFactory.sPackageInfo，这是 Chromium 读 versionCode 的来源之一。 */
    private static Object staticField() {
        try {
            Field f = Class.forName("android.webkit.WebViewFactory")
                    .getDeclaredField("sPackageInfo");
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    private static Object query(PackageManager pm, String pkg, int flags) {
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, flags);
            return pi == null ? "null" : "ok(" + pi.getLongVersionCode() + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "nfn";
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    private static String describe(Object o) {
        if (o == null) return "null";
        if (o instanceof String) return (String) o;
        if (o instanceof PackageInfo) {
            PackageInfo pi = (PackageInfo) o;
            return pi.packageName + "@" + pi.getLongVersionCode();
        }
        return String.valueOf(o);
    }

    private static String shortName(String pkg) {
        if (pkg.endsWith("google.android.webview")) return "gwebview";
        if (pkg.endsWith("android.webview")) return "awebview";
        if (pkg.endsWith("android.chrome")) return "chrome";
        return pkg;
    }
}

package com.hush.core.hook;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.Spanned;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.hush.utils.Diag;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 协议正文的原生兜底渲染。
 *
 * <p><b>已停用（1.6.36 起）。</b>当前没有任何地方调用这个类：装配入口
 * {@code LsHooks} 里已经把 {@code ProtocolBypass.start()} 注释掉了。保留代码
 * 只为记录结论 —— 正文拉取和 {@code Html.fromHtml} 都是通的，两次失败都出在
 * 「往哪儿贴」：挂父容器会被 SDK 重建 WebView 时清掉，挂 DecorView 又会被
 * 弹窗自己的 Window 盖住。要做对得直接往弹窗的 Window 里加，收益不值得。
 *
 * 背景：容器里 WebView 的 renderer 进程起不来（1.6.33 的日志里，
 * 连 about:blank 的 evaluateJavascript("1+1") 都 MISSING），
 * 所以协议弹窗里的正文区永远是灰的。
 *
 * 但正文本身是可以拿到的：
 * <pre>
 * curl 'https://protocol.unisdk.netease.com/api/template/v89/latest.html?base_protocol=1'
 *   -> 200, 180012 B, 正文在 class="content-text" 的 div 里（服务端渲染，非 JS 生成）
 * curl 'https://protocol.unisdk.netease.com/api/template/v5/latest.html'
 *   -> 200, 354302 B, 35928 字《网易游戏使用许可及服务协议》全文
 * </pre>
 *
 * 所以这里不碰 WebView，自己用 HttpURLConnection 把同一个 URL 拉下来，
 * 剥掉 script/style，取 content-text 的 innerHTML，交给 Html.fromHtml，
 * 再用一个 ScrollView+TextView 盖在 WebView 的位置上。
 *
 * 内容因此是官方实时的，不是本地 assets 里那份 2022 年的旧模板。
 * 只在 WebView 确实画不出来时才走这条路：一旦 WebView 自己能出内容，
 * 这里的 View 会被 SDK 自己的布局盖住，不影响原流程。
 */
public final class ProtocolText {

    /**
     * UA 必须带 unisdk/mobile。
     *
     * 协议页里用 /unisdk\/mobile/.test(userAgent) 判断是否走「协议模式」：
     * 不带这个标记时页面会删掉 .protocol-wrap、显示 .wrap 的降级提示，
     * 拿到的正文就不是用户看到的那一份。
     */
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Version/4.0 Chrome/120.0 Mobile Safari/537.36 "
                    + "unisdk/mobile";

    /** 只取这一个页面的正文，别把整个 180KB 都丢给 fromHtml。 */
    private static final String CONTENT_MARK = "class=\"content-text\"";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean sTried;
    private static volatile boolean sShown;

    private ProtocolText() {
    }

    /** 正文是否已经成功铺上去了。铺上了就不再自动跳过协议页。 */
    public static boolean shown() {
        return sShown;
    }

    /**
     * 从 ProtocolLauncher 手里拿 WebView 当前的 URL。
     * 必须在主线程调 —— WebView 的方法不是线程安全的。
     */
    public static void probe(final Object launcher) {
        if (sTried || launcher == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Object o = fieldOf(launcher, "mWebView");
                    if (!(o instanceof WebView)) return;
                    String u = ((WebView) o).getUrl();
                    if (u == null || !u.contains("protocol.unisdk.netease.com")) return;
                    maybeFetch(u, launcher);
                } catch (Throwable t) {
                    Diag.log("pt", "probe failed " + t);
                }
            }
        });
    }

    private static synchronized void maybeFetch(final String url, final Object launcher) {
        if (sTried) return;
        sTried = true;
        Diag.log("pt", "fetch " + url);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                fetch(url, launcher);
            }
        }, "HushProtoText");
        t.setDaemon(true);
        t.start();
    }

    private static void fetch(String url, Object launcher) {
        long t0 = android.os.SystemClock.uptimeMillis();
        String html;
        try {
            html = get(url);
        } catch (Throwable t) {
            Diag.log("pt", "http failed " + t);
            return;
        }
        Diag.log("pt", "got " + (html == null ? 0 : html.length()) + "B in "
                + (android.os.SystemClock.uptimeMillis() - t0) + "ms");
        if (html == null || html.length() < 500) {
            Diag.log("pt", "response too small, give up");
            return;
        }

        String inner = extract(html);
        if (inner == null) {
            Diag.log("pt", "content-text not found");
            return;
        }
        Diag.log("pt", "inner=" + inner.length() + "B");

        final Spanned sp;
        try {
            sp = Html.fromHtml(inner, Html.FROM_HTML_MODE_LEGACY);
        } catch (Throwable t) {
            Diag.log("pt", "fromHtml failed " + t);
            return;
        }
        Diag.log("pt", "spanned=" + sp.length());

        final Object target = launcher;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                overlay(target, sp);
            }
        });
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6_000);
        c.setReadTimeout(8_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            Diag.log("pt", "http " + code);
            c.disconnect();
            return null;
        }
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 17);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > 2_000_000) break;
        }
        in.close();
        c.disconnect();
        return new String(bo.toByteArray(), "UTF-8");
    }

    /**
     * 截出 {@code class="content-text"} 这个 div 的 innerHTML。
     *
     * 不用正则：正文里嵌套了几十个 div，正则匹配不了配对。
     * 这里做一次简单的 <div 计数，遇到 depth 归零就收尾。
     */
    private static String extract(String html) {
        int i = html.indexOf(CONTENT_MARK);
        if (i < 0) i = html.indexOf("content-text");
        if (i < 0) return null;

        int gt = html.indexOf('>', i);
        if (gt < 0) return null;
        int start = gt + 1;

        int depth = 1;
        int p = start;
        while (p < html.length()) {
            int open = html.indexOf("<div", p);
            int close = html.indexOf("</div", p);
            if (close < 0) break;
            if (open >= 0 && open < close) {
                ++depth;
                p = open + 4;
            } else {
                --depth;
                p = close + 5;
                if (depth == 0) return html.substring(start, close);
            }
        }
        return null;
    }

    /**
     * 当前贴在窗口上的正文层。守护逻辑靠这三个引用在布局被重建后重新贴回去。
     * 用 WeakReference 没必要 —— 它们和 Activity 同生命周期，Activity 没了
     * 守护线程也会因为拿不到 window 而退出。
     */
    private static volatile ScrollView sOverlay;
    private static volatile ViewGroup sRoot;
    private static volatile View sAnchor;
    private static volatile Object sLauncher;

    /** 守护次数 × 间隔 = 21 秒，覆盖 SDK 重建布局的那个窗口。 */
    private static final int GUARD_TIMES = 14;
    private static final long GUARD_MS = 1_500L;
    private static volatile int sGuardLeft;

    /**
     * 1.6.34 的教训：正文挂到 WebView 的直接父容器里，而 SDK 在加载失败后
     * 会把整个 WebView 换掉（旧 2458x1186 url=null vis=8，新 1957x708
     * about:blank 出现），父容器的子 View 一起被清，正文就"闪一下就没了"。
     *
     * 所以改挂到 window 的 DecorView —— SDK 动不到那一层 —— 再留一个守护，
     * 万一位置或尺寸变了就重贴。
     */
    private static final Runnable GUARD = new Runnable() {
        @Override
        public void run() {
            try {
                ScrollView sv = sOverlay;
                ViewGroup root = sRoot;
                if (sv == null || root == null) return;

                // SDK 换了新 WebView 就把锚点跟过去；没换（mWebView=null）就沿用旧的，
                // 位置不变，正文至少还在原处。
                Object o = sLauncher == null ? null : fieldOf(sLauncher, "mWebView");
                if (o instanceof View && ((View) o).isAttachedToWindow()) {
                    sAnchor = (View) o;
                }
                View anchor = sAnchor;
                if (anchor == null || !anchor.isAttachedToWindow()) {
                    Diag.log("pt", "guard: anchor gone");
                    return;
                }

                ViewGroup parent = sv.getParent() instanceof ViewGroup
                        ? (ViewGroup) sv.getParent() : null;
                if (parent != root) {
                    if (parent != null) {
                        try {
                            parent.removeView(sv);
                        } catch (Throwable ignored) {
                        }
                    }
                    root.addView(sv);
                    Diag.log("pt", "guard re-attach root="
                            + root.getClass().getSimpleName());
                }
                place(root, anchor, sv);
            } catch (Throwable t) {
                Diag.log("pt", "guard failed " + t);
            } finally {
                if (--sGuardLeft > 0) MAIN.postDelayed(this, GUARD_MS);
                else Diag.log("pt", "guard done");
            }
        }
    };

    /** 把正文盖到 WebView 的位置上（主线程）。 */
    private static void overlay(Object launcher, Spanned sp) {
        try {
            Object o = fieldOf(launcher, "mWebView");
            if (!(o instanceof View)) {
                Diag.log("pt", "overlay: no mWebView");
                return;
            }
            final View wv = (View) o;
            if (!wv.isAttachedToWindow()) {
                Diag.log("pt", "overlay: webview detached, skip");
                return;
            }

            // 优先挂 DecorView：SDK 只会重建自己的内容容器，不会清窗口根。
            Object act = launcher;
            ViewGroup root = null;
            if (act instanceof android.app.Activity) {
                android.view.Window win = ((android.app.Activity) act).getWindow();
                Object dec = win == null ? null : win.getDecorView();
                if (dec instanceof ViewGroup) root = (ViewGroup) dec;
            }
            if (root == null) {
                Object parent = wv.getParent();
                if (!(parent instanceof ViewGroup)) {
                    Diag.log("pt", "overlay: no root, parent=" + parent);
                    return;
                }
                root = (ViewGroup) parent;
            }

            TextView tv = new TextView(root.getContext());
            tv.setText(sp);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setTextColor(0xFF5A5A5A);
            tv.setLineSpacing(7f, 1.0f);
            tv.setPadding(40, 30, 40, 30);
            tv.setBackgroundColor(Color.WHITE);

            ScrollView sv = new ScrollView(root.getContext());
            sv.setBackgroundColor(Color.WHITE);
            sv.setFillViewport(true);
            sv.addView(tv, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            // 抬高一层，避免被同窗口里后加的 View 盖住
            try {
                sv.setElevation(30f);
            } catch (Throwable ignored) {
            }

            sLauncher = launcher;
            sRoot = root;
            sAnchor = wv;
            sOverlay = sv;

            root.addView(sv);
            place(root, wv, sv);

            sShown = true;
            Diag.log("pt", "overlay ok " + wv.getWidth() + "x" + wv.getHeight()
                    + " root=" + root.getClass().getSimpleName()
                    + " chars=" + sp.length());

            sGuardLeft = GUARD_TIMES;
            MAIN.removeCallbacks(GUARD);
            MAIN.postDelayed(GUARD, GUARD_MS);
        } catch (Throwable t) {
            Diag.log("pt", "overlay failed " + t);
        }
    }

    /**
     * 按屏幕坐标把正文层摆到锚点上方。
     *
     * DecorView 是 FrameLayout，它每次 onLayout 都会按 LayoutParams 重新摆放
     * 子 View —— 只调 setX/setY 会在下一帧被抹掉。所以对 FrameLayout 走
     * margin，其它容器才退回到 setX/setY。
     */
    private static void place(ViewGroup root, View anchor, View overlay) {
        try {
            int w = anchor.getWidth();
            int h = anchor.getHeight();
            if (w <= 0 || h <= 0) return;

            int[] a = new int[2];
            anchor.getLocationOnScreen(a);
            int[] r = new int[2];
            root.getLocationOnScreen(r);

            int x = a[0] - r[0] - root.getPaddingLeft();
            int y = a[1] - r[1] - root.getPaddingTop();

            if (root instanceof android.widget.FrameLayout) {
                android.widget.FrameLayout.LayoutParams lp =
                        new android.widget.FrameLayout.LayoutParams(w, h);
                lp.leftMargin = x;
                lp.topMargin = y;
                overlay.setLayoutParams(lp);
            } else {
                ViewGroup.LayoutParams lp = overlay.getLayoutParams();
                if (lp == null || lp.width != w || lp.height != h) {
                    overlay.setLayoutParams(new ViewGroup.LayoutParams(w, h));
                }
                overlay.setX(a[0] - r[0]);
                overlay.setY(a[1] - r[1]);
            }
        } catch (Throwable t) {
            Diag.log("pt", "place failed " + t);
        }
    }

    private static Object fieldOf(Object o, String name) {
        if (o == null) return null;
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
                return null;
            }
            c = c.getSuperclass();
        }
        return null;
    }
}

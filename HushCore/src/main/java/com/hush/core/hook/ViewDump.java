package com.hush.core.hook;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;

import com.hush.utils.Diag;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 纯被动的 View 树快照。
 *
 * 为什么不用 hook：前面三版为了看「有没有收到配置变化 / 有没有 startActivity /
 * WebView 加载到哪」，分别 hook 了 Activity 生命周期、startActivity、WebView.loadUrl。
 * 这些 hook 一旦转发不完美（HushHook 的 backup 在参数含泛型/重载时很容易错位），
 * 就会把原本能跑的调用变成 no-op —— 探针本身成了故障源，数据也全是假的。
 *
 * 这一版改成零 hook：只用反射读 {@code ActivityThread.mActivities} 里存活的
 * Activity 对象，定时把它们的 decorView 树打出来。
 *
 * 一张快照就能回答四个问题：
 * <ul>
 *   <li>屏幕上有几个 Activity，是哪个类（ProtocolLauncher 还是真正的游戏 Activity）；</li>
 *   <li>contentView 挂了几层、每层多大、是否 VISIBLE；</li>
 *   <li>有没有 SurfaceView / TextureView，surface 是否 valid、frame 多少；</li>
 *   <li>有没有 WebView，URL 是什么、进度多少、尺寸多少。</li>
 * </ul>
 *
 * Unity 只要跑起来，就必然有一个 SurfaceView（或 TextureView）并且 surface 有效；
 * 没有这个对象 = 引擎还没启动；有对象但 valid=0 = 引擎启动了但没拿到 surface。
 * 这两种情况的修法完全不同，必须靠这张表分开。
 */
public final class ViewDump {

    private static boolean sInstalled = false;

    /** 每次快照最多打多少个节点，防止树大时把 diag 刷爆。 */
    private static final int SNAPSHOTS = 20;
    private static final int MAX_NODES = 110;
    private static final int MAX_DEPTH = 10;

    private ViewDump() {
    }

    public static synchronized void install() {
        if (sInstalled) return;
        sInstalled = true;

        final Handler h = new Handler(Looper.getMainLooper());
        // 2s 后开始，之后每 2.5s 一次，共 SNAPSHOTS 次。
        // 1.6.29 只拍到第 8 次（29.3s），而用户往往 20 秒后才动手，
        // 弹窗和退出那几秒全在盲区里。拉到 14 次覆盖到 34.5s。
        for (int i = 0; i < SNAPSHOTS; ++i) {
            final int seq = i;
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        dump(seq);
                    } catch (Throwable t) {
                        Diag.log("vd", "#" + seq + " failed " + t);
                    }
                }
            }, 2000L + i * 2500L);
        }
        Diag.log("vd", "passive snapshot armed x" + SNAPSHOTS);
    }

    private static void dump(int seq) {
        List<Object> records = activities();
        Diag.log("vd", "#" + seq + " acts=" + records.size() + " libs=" + libs());

        if (records.isEmpty()) {
            Diag.log("vd", "#" + seq + " (no ActivityClientRecord with live activity)");
        }

        for (Object rec : records) {
            Object act = fieldOf(rec, "activity", "mActivity");
            if (!(act instanceof android.app.Activity)) continue;
            android.app.Activity a = (android.app.Activity) act;

            StringBuilder head = new StringBuilder(200);
            head.append('#').append(seq).append(" ACT ").append(a.getClass().getName());
            try {
                head.append(" state=").append(a.isFinishing() ? "finishing" : "alive");
                head.append(" destroyed=").append(a.isDestroyed() ? 1 : 0);
            } catch (Throwable ignored) {
            }
            try {
                Window w = a.getWindow();
                if (w != null) {
                    View d = w.getDecorView();
                    head.append(" decor=").append(d == null ? "null"
                            : (d.getWidth() + "x" + d.getHeight()));
                    WindowManager.LayoutParams lp = w.getAttributes();
                    if (lp != null) {
                        head.append(" fmt=").append(lp.format)
                                .append(" fl=0x").append(Integer.toHexString(lp.flags))
                                .append(" w=").append(lp.width).append(" h=").append(lp.height);
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                head.append(" theme=").append(themeName(a));
            } catch (Throwable ignored) {
            }
            Diag.log("vd", head.toString());

            // SDK 自己的状态字段。协议页空白时，最想知道的是「它手里到底有没有
            // 一个 WebView / 有没有 loading view / 停在哪个状态」。这些字段名
            // 加固后不会被混淆（反射字段名在 dex 里是字符串），能直接读。
            dumpFields(seq, a);

            try {
                Window w = a.getWindow();
                View d = w == null ? null : w.getDecorView();
                if (d != null) {
                    int[] budget = new int[]{MAX_NODES};
                    walk(seq, d, 0, budget, new StringBuilder());
                }
            } catch (Throwable t) {
                Diag.log("vd", "#" + seq + " tree err " + t);
            }
        }

        // mActivities 只能看到 Activity 自己的窗口。屏幕上的对话框、Toast、
        // 以及 Unity 可能自建的子窗口都不在里面。WindowManagerGlobal.mViews
        // 是「这个进程真正在合成哪些窗口」的唯一完整列表，
        // 所以再用它兜一遍 —— 这也是确认「那个协议框到底是 Activity 里的
        // 还是独立 Dialog 窗口」的唯一办法。
        dumpWindows(seq);
    }

    private static void dumpFields(int seq, Object a) {
        Class<?> c = a.getClass();
        for (int depth = 0; c != null && depth < 3; ++depth, c = c.getSuperclass()) {
            if (c.getName().startsWith("android.") || c.getName().startsWith("java.")) break;
            Field[] fs = c.getDeclaredFields();
            for (Field f : fs) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                boolean interesting = View.class.isAssignableFrom(t)
                        || android.app.Dialog.class.isAssignableFrom(t)
                        || android.webkit.WebView.class == t
                        || java.util.Map.class.isAssignableFrom(t)
                        || java.util.List.class.isAssignableFrom(t)
                        || android.os.Handler.class == t;
                if (!interesting) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(a);
                    StringBuilder sb = new StringBuilder(120);
                    sb.append('#').append(seq).append(" FLD ").append(f.getName())
                            .append(" : ").append(t.getSimpleName())
                            .append(" = ").append(v == null ? "null" : v.getClass().getSimpleName());
                    if (v instanceof View) {
                        View vv = (View) v;
                        sb.append(" ").append(vv.getWidth()).append('x').append(vv.getHeight())
                                .append(" vis=").append(vv.getVisibility());
                    }
                    Diag.log("vd", sb.toString());
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void dumpWindows(int seq) {
        try {
            Class<?> cls = Class.forName("android.view.WindowManagerGlobal");
            Method get = cls.getDeclaredMethod("getInstance");
            get.setAccessible(true);
            Object inst = get.invoke(null);
            if (inst == null) {
                Diag.log("vd", "#" + seq + " WMGlobal null");
                return;
            }

            Field fv = cls.getDeclaredField("mViews");
            fv.setAccessible(true);
            Object views = fv.get(inst);
            if (!(views instanceof List)) {
                Diag.log("vd", "#" + seq + " mViews not a list");
                return;
            }
            List<?> list = (List<?>) views;
            Diag.log("vd", "#" + seq + " WMGlobal windows=" + list.size());

            Object roots = null;
            try {
                Field fr = cls.getDeclaredField("mRoots");
                fr.setAccessible(true);
                roots = fr.get(inst);
            } catch (Throwable ignored) {
            }

            int n = list.size();
            for (int i = 0; i < n; ++i) {
                Object v = list.get(i);
                if (!(v instanceof View)) continue;
                StringBuilder sb = new StringBuilder(160);
                sb.append('#').append(seq).append(" WIN[").append(i).append(']');
                try {
                    if (roots instanceof List && i < ((List<?>) roots).size()) {
                        Object root = ((List<?>) roots).get(i);
                        Object lp = fieldOf(root, "mWindowAttributes");
                        if (lp != null) {
                            Object t = fieldOf(lp, "title");
                            sb.append(" title=").append(String.valueOf(t));
                            Object fl = fieldOf(lp, "flags");
                            if (fl instanceof Integer) {
                                sb.append(" fl=0x").append(Integer.toHexString((Integer) fl));
                            }
                            // type/alpha/packageName 决定这块窗口到底属于哪一层、
                            // 是不是透明、WMS 认的是谁的包名 —— 悬浮窗「加了但看不见」
                            // 时只有这三项能区分「被压在下面」和「alpha=0」。
                            Object ty = fieldOf(lp, "type");
                            if (ty instanceof Integer) {
                                sb.append(" type=").append(ty);
                            }
                            Object al = fieldOf(lp, "alpha");
                            if (al instanceof Float) {
                                sb.append(" alpha=").append(al);
                            }
                            Object pk = fieldOf(lp, "packageName");
                            if (pk != null) {
                                sb.append(" pkg=").append(pk);
                            }
                        }
                        Object view2 = fieldOf(root, "mView");
                        if (view2 != null && view2 != v) {
                            sb.append(" rootViewDiffers=1");
                        }
                        Object added = fieldOf(root, "mAdded");
                        if (added instanceof Boolean) {
                            sb.append(" added=").append(added);
                        }
                    }
                } catch (Throwable ignored) {
                }
                try {
                    sb.append(" shown=").append(((View) v).isShown() ? 1 : 0)
                            .append(" att=").append(((View) v).isAttachedToWindow() ? 1 : 0);
                } catch (Throwable ignored) {
                }
                Diag.log("vd", sb.toString());

                try {
                    int[] budget = new int[]{MAX_NODES};
                    walk(seq, (View) v, 0, budget, new StringBuilder());
                } catch (Throwable t) {
                    Diag.log("vd", "#" + seq + " win tree err " + t);
                }
            }
        } catch (Throwable t) {
            Diag.log("vd", "#" + seq + " WMGlobal err " + t);
        }
    }

    /**
     * 看 SDK 到底给这个 WebView 挂了哪个 WebViewClient。
     *
     * 如果 client 是 SDK 自己的子类，它很可能覆写了 shouldInterceptRequest
     * 或 shouldOverrideUrlLoading —— 那「空白」就不是渲染问题，而是请求被拦了。
     * 这两种情况的修法完全不同，必须先分开。
     */
    private static String clientName(WebView wv) {
        for (Class<?> c = WebView.class; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("mWebViewClient");
                f.setAccessible(true);
                Object o = f.get(wv);
                if (o == null) return "null";
                String n = o.getClass().getName();
                int i = n.lastIndexOf('.');
                return i > 0 ? n.substring(i + 1) : n;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return "err:" + t.getClass().getSimpleName();
            }
        }
        return "?";
    }

    private static void walk(int seq, View v, int depth, int[] budget, StringBuilder pad) {
        if (v == null || budget[0] <= 0 || depth > MAX_DEPTH) return;
        --budget[0];

        StringBuilder sb = new StringBuilder(180);
        sb.append('#').append(seq).append(' ').append(pad)
                .append(v.getClass().getSimpleName())
                .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                .append(" vis=").append(v.getVisibility())
                .append(" at=").append(v.getAlpha());

        // 「菜单能看见、点着没反应」最省事的判据：这个控件到底有没有挂点击监听。
        // lst=0 说明它只是个画出来的方块，事件根本没人接。
        try {
            sb.append(" clk=").append(v.isClickable() ? 1 : 0)
                    .append(" lst=").append(v.hasOnClickListeners() ? 1 : 0);
        } catch (Throwable ignored) {
        }

        // 资源 id 名是判断「这块空白本来该显示什么」的唯一线索：
        // 一个 id 叫 protocol_webview 的空 FrameLayout，和 id 叫 error_view 的
        // GONE FrameLayout，含义完全不同。
        try {
            int vid = v.getId();
            if (vid != View.NO_ID) {
                sb.append(" id=").append(resName(v, vid));
            }
        } catch (Throwable ignored) {
        }

        if (v instanceof android.view.ViewStub) {
            try {
                int layout = ((android.view.ViewStub) v).getLayoutResource();
                sb.append(" VIEWSTUB layout=").append(resName(v, layout));
            } catch (Throwable t) {
                sb.append(" VIEWSTUB-ERR");
            }
        }

        if (v instanceof SurfaceView) {
            try {
                SurfaceView sv = (SurfaceView) v;
                SurfaceHolder h = sv.getHolder();
                android.graphics.Rect r = h.getSurfaceFrame();
                Surface s = h.getSurface();
                sb.append(" SURFACEVIEW frame=")
                        .append(r == null ? "null" : (r.width() + "x" + r.height()))
                        .append(" valid=").append(s != null && s.isValid() ? 1 : 0);
            } catch (Throwable t) {
                sb.append(" SV-ERR ").append(t);
            }
        } else if (v instanceof TextureView) {
            try {
                TextureView tv = (TextureView) v;
                SurfaceTexture st = tv.getSurfaceTexture();
                sb.append(" TEXTUREVIEW avail=").append(tv.isAvailable() ? 1 : 0)
                        .append(" st=").append(st == null ? "null" : "ok");
            } catch (Throwable t) {
                sb.append(" TV-ERR ").append(t);
            }
        } else if (v instanceof WebView) {
            try {
                WebView wv = (WebView) v;
                String u = wv.getUrl();
                // prog 停在 10、contentHeight=0 是「导航从未 commit」的标志：
                // Chromium 的进度在 renderer 提交首帧之前不会超过 10。
                // 1.6.32 的日志里连 about:blank 都卡在 prog=10，
                // 说明卡点不在网络，而在渲染进程本身。
                sb.append(" WEBVIEW url=").append(u == null ? "null"
                                : (u.length() > 90 ? u.substring(0, 90) + "…" : u))
                        .append(" prog=").append(wv.getProgress())
                        .append(" ch=").append(wv.getContentHeight())
                        .append(" lay=").append(wv.getLayerType())
                        .append(" hw=").append(wv.isHardwareAccelerated() ? 1 : 0)
                        .append(" att=").append(wv.isAttachedToWindow() ? 1 : 0);
                String ti = wv.getTitle();
                if (ti != null && ti.length() > 0) {
                    sb.append(" ti=").append(ti.length() > 40 ? ti.substring(0, 40) + "…" : ti);
                }
                sb.append(" wc=").append(clientName(wv));
            } catch (Throwable t) {
                sb.append(" WV-ERR ").append(t);
            }
        }

        // 文字是唯一能把「SDK 到底在说什么」读出来的通道。
        // 之前只能看到「851x503 的对话框里有 751x236 的 TextView」，
        // 完全不知道它写的是「协议内容」还是「网络异常」——这两者修法相反。
        if (v instanceof android.widget.TextView) {
            try {
                CharSequence t = ((android.widget.TextView) v).getText();
                String str = t == null ? "null" : t.toString().replace('\n', '|');
                if (str.length() > 120) str = str.substring(0, 120) + "…";
                sb.append(" TEXT=\"").append(str).append('"');
            } catch (Throwable t) {
                sb.append(" TEXT-ERR");
            }
        }

        Diag.log("vd", sb.toString());

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            pad.append("  ");
            for (int i = 0; i < n && budget[0] > 0; ++i) {
                try {
                    walk(seq, g.getChildAt(i), depth + 1, budget, pad);
                } catch (Throwable ignored) {
                }
            }
            int len = pad.length();
            if (len >= 2) pad.setLength(len - 2);
        }
    }

    private static String resName(View v, int id) {
        try {
            return v.getResources().getResourceName(id);
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(id);
        }
    }

    /** 反射拿 ActivityThread.mActivities 里所有 ActivityClientRecord。 */
    private static List<Object> activities() {
        List<Object> out = new ArrayList<>();
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cur = at.getDeclaredMethod("currentActivityThread");
            cur.setAccessible(true);
            Object thread = cur.invoke(null);
            if (thread == null) return out;

            Field f = null;
            Class<?> c = thread.getClass();
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField("mActivities");
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) return out;
            f.setAccessible(true);
            Object map = f.get(thread);
            if (!(map instanceof Map)) return out;
            out.addAll(((Map<?, ?>) map).values());
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static Object fieldOf(Object o, String... names) {
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

    private static String themeName(Object a) {
        try {
            android.app.Activity act = (android.app.Activity) a;
            return String.valueOf(act.getTheme().getClass().getName());
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * 当前进程加载了哪些 native 库 —— 判断 Unity 有没有起来最直接的旁证。
     * 只取 basename 并去重，避免把 /proc/self/maps 整段打出来。
     */
    private static String libs() {
        Set<String> names = new LinkedHashSet<>();
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"));
            String line;
            while ((line = r.readLine()) != null) {
                int i = line.indexOf('/');
                if (i < 0) continue;
                String p = line.substring(i).trim();
                if (!p.endsWith(".so")) continue;
                int s = p.lastIndexOf('/');
                String base = s < 0 ? p : p.substring(s + 1);
                if (base.contains("unity") || base.contains("il2cpp")
                        || base.contains("main.") || base.contains("webviewchromium")
                        || base.contains("egl") || base.contains("GLES")
                        || base.contains("unisec")) {
                    names.add(base);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
        if (names.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) sb.append(',');
            sb.append(n);
        }
        return sb.toString();
    }
}

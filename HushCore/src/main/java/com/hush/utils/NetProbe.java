package com.hush.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/**
 * 真联网取证：DNS → TCP:443 → HTTPS，一步都不省。
 *
 * <p>为什么需要它：容器里 {@code ConnectivityManager} 说「移动数据已连接、
 * VALIDATED」，HMS 的页面却写「无网络」。前者只说明「系统认为有网」，
 * 不说明「这个进程真的能把包发出去」。两者之间还隔着 DNS、路由、
 * 权限（INTERNET）、TLS 握手四道关，任何一道断了都表现为「无网络」。</p>
 *
 * <p>这里用最原始的方式把四道关逐个走一遍，并把异常类名和 message 原样记下来。
 * 有了它就不需要再猜：</p>
 * <pre>
 *   netprobe: guest:com.huawei.hwid host=grs.dbankcloud.com
 *             dns=ok[49.4.x.x](23ms) tcp=ok(61ms) https=200(412ms)
 *   netprobe: ... https=ERR SSLHandshakeException:Certificate expired
 *   netprobe: ... dns=ERR UnknownHostException:Unable to resolve host ...
 * </pre>
 */
public final class NetProbe {

    /** 宿主和每个 guest 进程各跑一次就够。 */
    private static final AtomicBoolean HTTP_ONCE = new AtomicBoolean();
    private static final AtomicBoolean WEB_ONCE = new AtomicBoolean();

    /**
     * 三个目标各有各的意义：
     * <ul>
     *   <li>{@code grs.dbankcloud.com} —— HMS 的寻址服务，登录前第一件事就是问它；</li>
     *   <li>{@code hwid1.vmall.com} —— 华为账号登录页/协议页的宿主域名；</li>
     *   <li>{@code www.baidu.com} —— 对照组，它也不通就说明整机没网。</li>
     * </ul>
     */
    private static final String[] HOSTS = {
            "grs.dbankcloud.com", "hwid1.vmall.com", "www.baidu.com"
    };

    private NetProbe() {
    }

    /** 起三个线程并行探，最慢的一个决定总耗时（每个目标 ≤ 5s 超时）。 */
    public static void probe(final String where) {
        if (!HTTP_ONCE.compareAndSet(false, true)) return;
        for (final String host : HOSTS) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    one(where, host);
                }
            }, "HushNetProbe-" + host);
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * 带上下文版本：先把 INTERNET 权限的状态打出来。
     *
     * <p>「联网控制」类设置（EMUI/MIUI 的流量管理、第三方防火墙）会在不改
     * {@code ACCESS_NETWORK_STATE} 的前提下单独掐掉某个 uid 的 socket。
     * 那种情况下 {@code ConnectivityManager} 一切正常，TCP 却直接 EPERM，
     * 页面就写「无网络」。这一行能把这种「权限说没网」和「真没网」分开。</p>
     */
    public static void probe(Context ctx, String where) {
        if (ctx != null) {
            try {
                int net = ctx.checkCallingOrSelfPermission("android.permission.INTERNET");
                int st = ctx.checkCallingOrSelfPermission("android.permission.ACCESS_NETWORK_STATE");
                Diag.log("netprobe", where + " perm INTERNET=" + permStr(net)
                        + " ACCESS_NETWORK_STATE=" + permStr(st)
                        + " uid=" + android.os.Process.myUid());
            } catch (Throwable t) {
                Diag.log("netprobe", where + " perm 查询失败 " + brief(t));
            }
        }
        probe(where);
    }

    private static String permStr(int v) {
        return v == 0 ? "GRANTED" : "DENIED(" + v + ")";
    }

    private static void one(String where, String host) {
        long t0 = SystemClock.elapsedRealtime();

        String dns;
        try {
            InetAddress[] as = InetAddress.getAllByName(host);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < as.length && i < 3; i++) {
                if (i > 0) sb.append(',');
                sb.append(as[i].getHostAddress());
            }
            dns = "ok[" + sb + "]";
        } catch (Throwable e) {
            dns = "ERR " + brief(e);
        }
        long t1 = SystemClock.elapsedRealtime();

        String tcp;
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(host, 443), 5000);
            tcp = "ok";
        } catch (Throwable e) {
            tcp = "ERR " + brief(e);
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
        }
        long t2 = SystemClock.elapsedRealtime();

        String http;
        HttpsURLConnection c = null;
        try {
            c = (HttpsURLConnection) new URL("https://" + host + "/").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "HushSpace-NetProbe");
            int code = c.getResponseCode();
            http = "http=" + code;
        } catch (Throwable e) {
            http = "ERR " + brief(e);
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
        long t3 = SystemClock.elapsedRealtime();

        Diag.log("netprobe", where + " host=" + host
                + " dns=" + dns + "(" + (t1 - t0) + "ms)"
                + " tcp=" + tcp + "(" + (t2 - t1) + "ms)"
                + " https=" + http + "(" + (t3 - t2) + "ms)");
    }

    /**
     * WebView 自己的联网取证。
     *
     * <p>协议页（{@code PrivacyPolicyFeatureActivity}）就是 WebView。HTTP 探针
     * 通、页面还写「无网络」，那就只能是 WebView 这条链路的事：渲染进程、
     * 证书、代理、net::ERR_*。这里用一个独立 WebView 加载同一个域名，
     * 把 {@code onReceivedError} 的 errorCode / description 原样打出来。</p>
     *
     * <p>延迟 6 秒执行，避开协议页自己的加载；结果出来立刻销毁，不留在界面上。</p>
     */
    public static void probeWebView(final Context ctx, final String where,
                                    final String url, long delayMs) {
        if (ctx == null) return;
        if (!WEB_ONCE.compareAndSet(false, true)) return;
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                web(ctx, where, url);
            }
        }, delayMs);
    }

    private static void web(Context ctx, String where, String url) {
        android.webkit.WebView wv = null;
        try {
            // WebView 的 profile / cache / cookies 全写在 dataDir 下。容器里这个
            // 路径是 guest 包名，native 层不做重定向，写不进去的话 Chromium
            // 会直接判定「网络不可用」，页面就写「无网络」。先把路径和可写性打出来。
            String dataDir = "?";
            try {
                java.io.File d = ctx.getDataDir();
                dataDir = d + " exists=" + d.exists() + " canWrite=" + d.canWrite();
            } catch (Throwable t) {
                dataDir = "ERR " + brief(t);
            }
            String cacheDir = "?";
            try {
                java.io.File d = ctx.getCacheDir();
                cacheDir = d + " exists=" + d.exists() + " canWrite=" + d.canWrite();
            } catch (Throwable t) {
                cacheDir = "ERR " + brief(t);
            }
            String pkg = "?";
            try {
                pkg = String.valueOf(android.webkit.WebView.getCurrentWebViewPackage());
            } catch (Throwable ignored) {
            }
            Diag.log("netweb", where + " dataDir=" + dataDir + " cacheDir=" + cacheDir
                    + " provider=" + pkg);

            wv = new android.webkit.WebView(ctx);
            final long t0 = SystemClock.elapsedRealtime();
            wv.setWebViewClient(new android.webkit.WebViewClient() {
                @Override
                public void onPageFinished(android.webkit.WebView view, String u) {
                    Diag.log("netweb", where + " finished " + u
                            + " (" + (SystemClock.elapsedRealtime() - t0) + "ms)");
                }

                @Override
                public void onReceivedError(android.webkit.WebView view, int code,
                                            String desc, String failingUrl) {
                    Diag.log("netweb", where + " ERROR code=" + code + " " + desc
                            + " url=" + failingUrl);
                }

                @Override
                public void onReceivedHttpError(android.webkit.WebView view,
                                                android.webkit.WebResourceRequest req,
                                                android.webkit.WebResourceResponse resp) {
                    Diag.log("netweb", where + " httpError " + resp.getStatusCode()
                            + " url=" + (req == null ? "?" : req.getUrl()));
                }
            });
            wv.getSettings().setJavaScriptEnabled(false);
            Diag.log("netweb", where + " load " + url);
            wv.loadUrl(url);

            final android.webkit.WebView victim = wv;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        victim.stopLoading();
                        victim.destroy();
                        Diag.log("netweb", where + " destroyed");
                    } catch (Throwable ignored) {
                    }
                }
            }, 10000);
        } catch (Throwable t) {
            Diag.log("netweb", where + " 创建失败 " + brief(t));
            if (wv != null) {
                try {
                    wv.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 异常带上最内层 cause，否则 SSL 那类包装过的一层看不出原因。 */
    private static String brief(Throwable e) {
        Throwable c = e;
        for (int i = 0; i < 6 && c.getCause() != null && c.getCause() != c; i++) {
            c = c.getCause();
        }
        String msg = c.getMessage();
        if (msg == null) msg = "";
        if (msg.length() > 120) msg = msg.substring(0, 120);
        return c.getClass().getSimpleName() + ":" + msg;
    }
}

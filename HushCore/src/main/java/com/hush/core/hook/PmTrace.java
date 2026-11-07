package com.hush.core.hook;

import com.hush.utils.Diag;

import java.util.HashMap;
import java.util.Map;

/**
 * getPackageInfo 回落的取证记录。
 *
 * 1.6.31 第一次抓到光遇真正的死因（不是闪退，也不是退出）：
 *
 * <pre>
 * FATAL: UNCAUGHT #1 via=main thread=main/1
 * java.util.ServiceConfigurationError: Attempt to read from field
 *   'int android.content.pm.PackageInfo.versionCode' on a null object reference
 *   in method 'void WV.s02.&lt;init&gt;()'
 *     at WV.o02.d(chromium-SystemWebViewGoogle...)
 *     at org.chromium.android_webview.AwBrowserProcess.b(...)
 *     at com.android.webview.chromium.WebViewChromium.init(...)
 *     at android.webkit.WebView.&lt;init&gt;(WebView.java:495)
 *     at com.netease.ntunisdk.external.protocol.view.UniWebView.&lt;init&gt;(UniWebView.java:70)
 *     at ProtocolLauncher.initWebView(ProtocolLauncher.java:306)
 *     at ProtocolLauncher.showProtocol(ProtocolLauncher.java:187)
 * </pre>
 *
 * 即：容器把某个包的 PackageInfo 返回成了 null，Chromium 只 catch 异常、不判空，
 * 直接读 versionCode 就 NPE，异常穿透到主线程 Looper，最后被易盾的 handler
 * 递归两万层埋掉。
 *
 * 这个类只负责「谁的哪次查询落到了 null」这一条取证，不参与业务判断：
 * 同一组 (接口, 包名, 结果) 只打第一次，避免 getPackageInfo 的高频调用刷爆日志。
 */
public final class PmTrace {

    /** 最多记录多少组不同的查询，防止异常情况下无限增长。 */
    private static final int MAX_UNIQUE = 60;

    /** 调用栈里最多回看多少层业务帧。 */
    private static final int STACK_DEPTH = 6;

    private static final Map<String, Integer> COUNTS = new HashMap<>();

    private PmTrace() {
    }

    /**
     * @param api    被调用的接口名，例如 getPackageInfo
     * @param pkg    查询的包名
     * @param flags  flags 原值
     * @param result host / hidden:xxx / nfn / null
     */
    public static synchronized void hit(String api, String pkg, int flags, String result) {
        String key = api + '|' + pkg + '|' + result;
        Integer c = COUNTS.get(key);
        if (c == null) {
            if (COUNTS.size() >= MAX_UNIQUE) return;
            COUNTS.put(key, 1);
            Diag.log("pm", api + " pkg=" + pkg
                    + " flags=0x" + Integer.toHexString(flags)
                    + " -> " + result
                    + " caller=" + caller());
            return;
        }
        COUNTS.put(key, c + 1);
        // 只在中转点报一次数量，避免每次查询都写一行
        if (c + 1 == 5 || c + 1 == 50 || c + 1 == 500) {
            Diag.log("pm", api + " pkg=" + pkg + " " + result + " x" + (c + 1));
        }
    }

    /** 取业务侧调用者，跳过容器自身、反射和 binder 的帧。 */
    private static String caller() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (int i = 2; i < st.length && n < STACK_DEPTH; i++) {
                String cn = st[i].getClassName();
                if (cn.startsWith("com.hush.")
                        || cn.startsWith("java.lang.reflect.")
                        || cn.startsWith("android.os.")
                        || cn.startsWith("com.android.internal.os.")) {
                    continue;
                }
                if (n > 0) sb.append(" <- ");
                sb.append(st[i].getMethodName()).append('@').append(simple(cn));
                n++;
            }
            return n == 0 ? "?" : sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String simple(String cn) {
        int i = cn.lastIndexOf('.');
        return i < 0 ? cn : cn.substring(i + 1);
    }
}

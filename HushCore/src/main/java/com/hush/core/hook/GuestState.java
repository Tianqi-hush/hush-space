package com.hush.core.hook;

import android.content.Context;

import com.hush.core.env.BEnvironment;
import com.hush.utils.Diag;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * guest 自己的持久状态快照。
 *
 * 现在唯一还说不清的事是：{@code ProtocolLauncher} 把页面布局撑起来了，
 * 但 `mWebView` 一直是 null、三块内容全 GONE。UniSDK 的协议页在真正建
 * WebView 之前会先确认「协议内容从哪来」和「本机是否已同意」，这两件事
 * 都会在 shared_prefs / files 里留下痕迹。
 *
 * 所以这里直接去读 guest 沙盒里的文件，不需要 hook 任何方法：
 *  - shared_prefs/*.xml 全文（协议版本、同意状态、设备 id 都在里面）
 *  - files/ 下的一级目录和文件大小（看它有没有落盘过任何东西）
 *  - 顺带验证一次 assets 能不能读（协议正文就在 assets 里）
 */
public final class GuestState {

    private static volatile boolean sStarted;

    private GuestState() {
    }

    public static synchronized void start(final String pkg) {
        if (sStarted) return;
        sStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(6_000);
                dumpPrefs(pkg, "t6");
                dumpAssets();
                sleep(9_000);
                dumpPrefs(pkg, "t15");
            }
        }, "HushGuestState");
        t.setDaemon(true);
        t.start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Throwable ignored) {
        }
    }

    private static void dumpPrefs(String pkg, String tag) {
        try {
            File dir = new File(BEnvironment.getDataDir(pkg, com.hush.app.BActivityThread.getUserId()),
                    "shared_prefs");
            File[] files = dir.listFiles();
            if (files == null) {
                Diag.log("st", tag + " prefs dir missing " + dir);
                return;
            }
            Diag.log("st", tag + " prefs count=" + files.length);
            for (File f : files) {
                if (!f.isFile()) continue;
                String body = readHead(f, 900);
                Diag.log("st", tag + " PREF " + f.getName() + " len=" + f.length()
                        + " => " + body.replace('\n', ' '));
            }
        } catch (Throwable t) {
            Diag.log("st", tag + " prefs failed " + t);
        }
    }

    private static String readHead(File f, int max) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = r.read()) > 0) {
                sb.append((char) c);
                if (sb.length() >= max) {
                    sb.append("…");
                    break;
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "<" + t + ">";
        } finally {
            try {
                if (r != null) r.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 协议正文在 assets 里（unisdk_protocol_default_txt，56KB）。
     * 如果这一步打不开，那「页面空白」就有了直接解释：SDK 拿不到正文，
     * 干脆什么都不显示。这是容器里最容易出问题、又最少被验证的一环。
     */
    private static void dumpAssets() {
        try {
            Context ctx = com.hush.HushCore.getContext()
                    .createPackageContext("com.netease.sky", Context.CONTEXT_IGNORE_SECURITY);
            java.io.InputStream in = ctx.getAssets().open("unisdk_protocol_default_txt");
            byte[] buf = new byte[32];
            int n = in.read(buf);
            in.close();
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < n && i < 16; i++) {
                hex.append(String.format("%02x", buf[i]));
            }
            Diag.log("st", "assets protocol txt ok first=" + n + "B head=" + hex);
        } catch (Throwable t) {
            Diag.log("st", "assets protocol txt FAILED " + t);
        }
    }
}

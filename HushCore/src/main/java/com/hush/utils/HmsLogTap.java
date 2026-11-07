package com.hush.utils;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 2.6.68：把 HMS / 华为 SDK 自己的 logcat 抓进 diag。
 *
 * <p>登录、账号这些逻辑全在 HMS 内部，出问题时它把错误码直接打进 logcat
 * （{@code wl0} / {@code HwID} / {@code HMS-}），界面上什么都不显示——用户反馈
 * “点什么都没发生”就是这么来的。容器进程和 HMS 共用宿主 uid，所以本进程能
 * 用 {@code logcat -d} 读到同 uid 的日志。</p>
 */
public final class HmsLogTap {

    private static final Object LOCK = new Object();
    private static long sLastAt;
    private static boolean sRunning;

    private HmsLogTap() {
    }

    /** 检测到 SDK 在向 HMS 要账号时调用；内部按 20 秒节流，只跑一次短命令。 */
    public static void kick(final String why) {
        synchronized (LOCK) {
            long now = SystemClock.uptimeMillis();
            if (sRunning || now - sLastAt < 20000) return;
            sLastAt = now;
            sRunning = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 挂 3 秒再抓，把 SDK 处理结果、HMS 的应答一起带上
                    Thread.sleep(3000);
                    dump(why);
                } catch (Throwable t) {
                    Diag.log("lgc", "抓取失败 " + t);
                } finally {
                    synchronized (LOCK) {
                        sRunning = false;
                    }
                }
            }
        }, "hms-logcat");
        t.setDaemon(true);
        t.start();
    }

    private static void dump(String why) {
        Process p = null;
        BufferedReader r = null;
        try {
            // ProcessBuilder.start / readLine 都抛 IOException，统一在这里兜住
            // （2.6.68 首次编译漏了 catch，见 13s 编译失败）
            p = new ProcessBuilder("/system/bin/logcat", "-d", "-v", "threadtime", "-t", "600")
                    .redirectErrorStream(true).start();
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<String> keep = new ArrayList<>();
            int total = 0;
            String line;
            long end = SystemClock.uptimeMillis() + 4000;
            while ((line = r.readLine()) != null) {
                total++;
                // 游戏进程 / 没有 tag 的 native 行也要留，HMS SDK 的错误码可能出现在任意一行
                if (interesting(line)) {
                    keep.add(line);
                    if (keep.size() >= 150) break;
                }
                if (SystemClock.uptimeMillis() > end) break;
            }
            Diag.log("lgc", "why=" + why + " total=" + total + " kept=" + keep.size());
            for (String s : keep) Diag.log("lgc", s);
        } catch (java.io.IOException e) {
            Diag.log("lgc", "抓取失败 " + e);
        } finally {
            CloseUtils.close(r);
            if (p != null) p.destroy();
        }
    }

    private static boolean interesting(String line) {
        if (line == null) return false;
        String l = line.toLowerCase();
        if (l.contains("hms") || l.contains("hwid") || l.contains("huawei")) return true;
        // 错误码形式：E/xxx(1234) 或者 907135xxx
        if (l.contains(" 907135")) return true;
        if (l.startsWith("e/") || l.contains(" e/")) return true;
        return false;
    }
}

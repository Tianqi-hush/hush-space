package com.hush.core.hook;

import com.hush.utils.Diag;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 把 guest 自己的 logcat 搬进 diag.log。
 *
 * 为什么需要它：光遇停在 {@code ProtocolLauncher} 时，SDK 自己会往 logcat 写
 * 它卡在哪一步，但这些行宿主这边拿不到。
 *
 * 1.6.28 用的是常驻 {@code logcat --pid=} 流式读，结果只打出一行
 * "logcat attached" 就再没有任何数据 —— 连接建立了但不推送。这一版改成
 * 定时 {@code logcat -d} 快照（dump 完就退出），并且把 stderr 一起收，
 * 这样即使被 SELinux 拒绝，也能看到拒绝原因而不是一片空白。
 */
public final class GuestLog {

    /** Diag 自己也往 logcat 写，必须排除，否则会自己喂自己。 */
    private static final String SELF_TAG = " HushDiag ";

    /**
     * 2.6.39：HMS 的协议页只活 2~4 秒，7 秒才第一次 dump 什么都抓不到。
     * 改成 3 秒后开始、每 4 秒一次、共 6 次（≈23 秒），覆盖整个跳板过程。
     */
    private static final int DUMPS = 6;
    private static final long FIRST_MS = 3_000L;
    private static final long INTERVAL_MS = 4_000L;
    private static final int PER_DUMP = 300;

    private static volatile boolean sStarted;

    private GuestLog() {
    }

    public static synchronized void start() {
        if (sStarted) return;
        sStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "HushGuestLog");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        for (int i = 0; i < DUMPS; i++) {
            try {
                Thread.sleep(i == 0 ? FIRST_MS : INTERVAL_MS);
            } catch (Throwable ignored) {
            }
            dump(i);
        }
    }

    private static void dump(int idx) {
        Process p = null;
        BufferedReader r = null;
        try {
            String pid = String.valueOf(android.os.Process.myPid());
            // --pid 只回本进程的行，比事后用 indexOf 过滤靠谱得多；
            // -v threadtime 才会把 pid 写进行首（-v time 没有 pid 列，
            // 1.6.29 的 total=1 kept=0 就是这么来的）。
            p = Runtime.getRuntime().exec(new String[]{
                    "/system/bin/logcat", "-d", "-v", "threadtime",
                    "--pid=" + pid, "-t", String.valueOf(PER_DUMP)});
            p.getOutputStream().close();
            r = new BufferedReader(new InputStreamReader(p.getInputStream()), 8192);
            StringBuilder err = new StringBuilder();
            BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            String el;
            while ((el = er.readLine()) != null) {
                if (err.length() < 300) err.append(el).append(';');
            }
            er.close();

            int kept = 0, total = 0;
            String line;
            String first = null;
            while ((line = r.readLine()) != null) {
                total++;
                if (first == null && !line.startsWith("---------")) first = line;
                if (line.indexOf(SELF_TAG) >= 0) continue;
                if (line.startsWith("--------- beginning of")) continue;
                // 只看本进程：dump 是全缓冲区，别的进程的行没有意义。
                if (line.indexOf(pid) < 0) continue;
                Diag.log("lg", line);
                kept++;
                if (kept >= PER_DUMP) break;
            }
            int code = -1;
            try {
                code = p.waitFor();
            } catch (Throwable ignored) {
            }
            // first= 是 1.6.29 缺的那半句：total=1 时至少能看到那一行是什么，
            // 从而区分「SELinux 拒绝」「缓冲区为空」还是「只有一行头」。
            Diag.log("lg", "dump#" + idx + " total=" + total + " kept=" + kept
                    + " rc=" + code
                    + (first != null ? " first=" + first : " first=<none>")
                    + (err.length() > 0 ? " err=" + err : ""));
        } catch (Throwable t) {
            Diag.log("lg", "dump#" + idx + " failed " + t);
        } finally {
            try {
                if (r != null) r.close();
            } catch (Throwable ignored) {
            }
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }
}

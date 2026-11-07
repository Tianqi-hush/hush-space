package com.hush.space.core;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 抓取本 uid 的 logcat。
 *
 * 用途：目标应用在 native 层被信号打死时，Java 侧收不到任何异常，
 * 但 debuggerd 会把 "Fatal signal 11 ..." 和完整回溯写进 logcat。
 * 从 Android 4.1 起应用只能读到自己 uid 的日志，而 guest 进程正好
 * 跑在我们的 uid 下，所以自己抓就够用，不需要 root。
 */
public final class LogcatTap {

    private static volatile boolean sRunning = false;
    private static volatile Process sProc;

    private LogcatTap() {
    }

    public static synchronized void start(Context context, long durationMs) {
        if (sRunning || context == null) return;
        sRunning = true;
        File dir = context.getExternalFilesDir(null);
        final File out = new File(dir == null ? context.getFilesDir() : dir, "logcat.txt");
        final long duration = durationMs <= 0 ? 60_000L : durationMs;

        Thread t = new Thread(() -> {
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(out, false);
                // 容器里 PATH 未必有 logcat，直接用绝对路径，否则会
                // exec 失败、文件留空（上一版就是这个问题）。
                sProc = Runtime.getRuntime().exec(new String[]{
                        "/system/bin/logcat", "-v", "threadtime"});
                BufferedReader r = new BufferedReader(new InputStreamReader(sProc.getInputStream()));
                long end = System.currentTimeMillis() + duration;
                String line;
                while (System.currentTimeMillis() < end && (line = r.readLine()) != null) {
                    fos.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    if (sProc != null) sProc.destroy();
                } catch (Throwable ignored) {
                }
                try {
                    if (fos != null) fos.close();
                } catch (Throwable ignored) {
                }
                // 实时管道经常因为容器把 stdout 截断而拿不到内容，
                // 停止后再整段读一次缓冲区，保证导出时一定有东西。
                dumpTo(out, 4000);
                sRunning = false;
            }
        }, "HushCore-LogcatTap");
        t.setDaemon(true);
        t.start();
    }

    /** 把当前 logcat 缓冲区整段追加到文件。 */
    private static void dumpTo(File out, int lines) {
        Process p = null;
        java.io.FileOutputStream fos = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{
                    "/system/bin/logcat", "-d", "-v", "threadtime",
                    "-t", String.valueOf(lines)});
            fos = new java.io.FileOutputStream(out, true);
            byte[] buf = new byte[8192];
            int r;
            java.io.InputStream in = p.getInputStream();
            while ((r = in.read(buf)) > 0) {
                fos.write(buf, 0, r);
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (Throwable ignored) {
            }
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    public static File file(Context context) {
        File dir = context.getExternalFilesDir(null);
        return new File(dir == null ? context.getFilesDir() : dir, "logcat.txt");
    }

    /**
     * 直接读系统 crash buffer。
     *
     * crash buffer 只放 native 崩溃与 Java 未捕获异常，条目极少，一条 logcat -d -b crash
     * 就能拿到完整回溯，比翻 main buffer 靠关键字过滤可靠得多。
     */
    public static String readCrashBuffer(int maxLines) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-b", "crash", "-v", "threadtime",
                    "-t", String.valueOf(maxLines <= 0 ? 150 : maxLines)});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 启动目标应用后延迟一段时间，把 crash buffer 补写进 diag.log。
     *
     * 这样用户只要复制 diag.log 一个文件，就能带上 native 崩溃回溯，
     * 不必再走"设置里长按导出"那一步。
     */
    public static void scheduleCrashDump(Context context, long delayMs) {
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMs <= 0 ? 6000L : delayMs);
            } catch (Throwable ignored) {
            }
            try {
                String crash = readCrashBuffer(150);
                if (crash != null && !crash.trim().isEmpty()) {
                    Diag.appendRaw(app, "\n===== crash buffer =====\n" + crash);
                } else {
                    Diag.appendRaw(app, "\n===== crash buffer =====\n(空：没有 Fatal signal / FATAL EXCEPTION 记录，"
                            + "说明进程是被 _exit() 直接结束的)\n");
                }
            } catch (Throwable ignored) {
            }
        }, "HushCore-CrashDump");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 崩溃摘要：只挑出 debuggerd / AndroidRuntime 的关键行。
     * 放在导出内容最前面，即使后面被消息长度截断也能拿到。
     */
    public static String crashDigest(Context context, int maxLines) {
        String all = tail(context, 5000);
        if (all.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String line : all.split("\n")) {
            boolean hit = line.contains("*** *** ***")
                    || line.contains("Fatal signal")
                    || line.contains("FATAL EXCEPTION")
                    || line.contains("Abort message")
                    || line.contains("backtrace:")
                    || line.contains("pc 0000")
                    || line.contains("has died")
                    || line.contains("ANR in")
                    || line.contains("Force finishing")
                    || line.contains("E DEBUG")
                    || line.contains("F DEBUG")
                    || line.contains("libBootloader")
                    || line.contains("libsec")
                    || line.contains("nesec");
            if (hit) {
                sb.append(line).append('\n');
                if (++n >= maxLines) break;
            }
        }
        return sb.toString();
    }

    /** 只取尾部：崩溃签名一定在最后，前面的系统日志没有价值。 */
    public static String tail(Context context, int maxLines) {
        File f = file(context);
        if (f == null || !f.exists()) return "";
        try {
            BufferedReader r = new BufferedReader(new java.io.FileReader(f));
            java.util.ArrayDeque<String> keep = new java.util.ArrayDeque<>();
            String line;
            while ((line = r.readLine()) != null) {
                // 我们自己的埋点已经在 diag.log 里了，这里只保留第三方/系统的输出
                if (line.contains("HushDiag")) continue;
                if (keep.size() >= maxLines) keep.pollFirst();
                keep.addLast(line);
            }
            r.close();
            StringBuilder sb = new StringBuilder();
            for (String s : keep) sb.append(s).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}

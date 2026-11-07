package com.hush.core.hook;

import android.util.Log;

import com.hush.core.VNative;
import com.hush.utils.Diag;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 直接截 guest 自己的 {@link Log} 调用。
 *
 * 为什么不再用 logcat：1.6.29 的三次 dump 全是 {@code total=1 kept=0} ——
 * 这个 ROM（SDK 37）上非 debuggable 进程读 logd 只拿得到缓冲区头，
 * 一行实际日志都没有。改命令参数治不了权限，只能换通道。
 *
 * 代价必须说清楚：{@code println_native} 是全应用最热的调用之一，
 * hook 之后每次都要过一次 Java 回调 + 反射 backup。所以设了三道闸，
 * 任何一道触发都会在 diag 里留一行，不静默：
 *
 * <ol>
 *   <li>只记 WARN 以上，或 tag/msg 命中 SDK 关键词（正常日志不记）；</li>
 *   <li>总量 {@link #MAX_LINES} 行封顶，超了只放行不记录；</li>
 *   <li>调用总数超过 {@link #MAX_CALLS} 次自动摘钩，回到原生实现。</li>
 * </ol>
 *
 * 安装时会打一条 {@code HushLogTapProbe} 自检：只有回调真的被调到，
 * 才把状态标成 verified。没调到就说明 HushHook 在这个 ART 上 hook 不了
 * native 方法，此时立刻摘钩，绝不留一个「装上了但静默污染日志」的钩子。
 */
public final class LogTap {

    /**
     * 已被 1.6.31 停用，**不要在任何地方调用 {@link #install()}**。
     *
     * 1.6.30 的实测结果：hook 完成后 9ms，guest 主线程在第一次 Log 调用
     * 走进 HushHook bridge 时直接 {@code _exit(0)}（caller=libandroid_runtime.so），
     * 42 轮重启全部死在同一行，连自检日志都来不及写。
     * 结论：println_native 是全应用最热的方法，动它等于在易盾面前举手。
     * 保留这个文件只为记录结论，装配入口已经摘掉。
     */

    /** Diag 自己的 tag。它内部会调 Log.i，必须在这里断掉递归。 */
    private static final String SELF_TAG = "HushDiag";

    /** 记录上限。到顶后仍然正常放行，只是不再写文件。 */
    private static final int MAX_LINES = 400;

    /** 调用总量闸。超过就摘钩，避免在日志风暴里拖慢 guest。 */
    private static final long MAX_CALLS = 20_000L;

    /**
     * 驻留时间闸：90 秒后自动摘钩。
     *
     * 启动阶段之外不再需要它（协议页要么已经被绕过，要么早就该出现），
     * 而 println_native 是全应用最热的方法，钩子挂得越久越容易被加固壳
     * 的完整性检查扫到。观测窗口够用就行，不常驻。
     */
    private static final long MAX_AGE_MS = 90_000L;

    /** 关键词：只捞和「卡在哪一步」有关的 tag/正文。 */
    private static final String[] KEYS = {
            "unisdk", "protocol", "webview", "netease", "tgc", "gamesdk",
            "ntes", "mpay", "ngwebview", "unisec", "crash", "ntunisdk",
    };

    /** 防递归：Diag.log 内部会调 Log.i，重入时直接放行。 */
    private static final ThreadLocal<Boolean> IN_CALLBACK = new ThreadLocal<Boolean>();

    private static volatile Method sBackup;
    private static volatile Method sTarget;
    private static volatile boolean sInstalled;
    private static volatile boolean sArmed;
    private static volatile boolean sDisarming;
    private static volatile int sLines;
    private static volatile long sCalls;
    private static volatile long sArmedAt;
    private static volatile String sState = "idle";

    private LogTap() {
    }

    public static final class Hooker {
        /**
         * 返回类型必须和 target 一致：println_native 返回 int，
         * HushHook 的 bridge 是按 target 的返回类型选 CallIntMethod 的，
         * 这里若声明成 Object，取到的会是引用值的低 32 位。
         */
        public int callback(Object[] args) {
            long n = ++sCalls;

            if (n > MAX_CALLS) {
                disarm("call cap " + MAX_CALLS);
            } else if (sArmed && android.os.SystemClock.uptimeMillis() - sArmedAt > MAX_AGE_MS) {
                disarm("age " + MAX_AGE_MS);
            } else if (sArmed && !Boolean.TRUE.equals(IN_CALLBACK.get())) {
                try {
                    int prio = (Integer) args[1];
                    String tag = (String) args[2];
                    String msg = (String) args[3];
                    if (shouldRecord(prio, tag, msg)) {
                        IN_CALLBACK.set(Boolean.TRUE);
                        try {
                            Diag.log("lg", tag + ": " + msg);
                            sLines++;
                            if (sLines >= MAX_LINES) disarm("line cap " + MAX_LINES);
                        } finally {
                            IN_CALLBACK.remove();
                        }
                    }
                } catch (Throwable ignored) {
                    // 解析失败就当没看见，绝不能因为记录本身把 guest 的日志打没。
                }
            }

            try {
                Object r = HookInvoke.call(sBackup, args);
                return r instanceof Integer ? (Integer) r : 0;
            } catch (Throwable t) {
                disarm("backup failed " + t);
                return 0;
            }
        }
    }

    private static boolean shouldRecord(int prio, String tag, String msg) {
        if (tag != null && SELF_TAG.equals(tag)) return false;
        if (prio >= Log.WARN) return true;

        String s = (tag == null ? "" : tag) + ' ' + (msg == null ? "" : msg);
        s = s.toLowerCase(Locale.US);
        for (String k : KEYS) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    /**
     * @return 状态串，直接写进 diag：{@code ok verified=true} 表示回调确认被调到。
     */
    public static synchronized String install() {
        if (sInstalled) return sState;
        sInstalled = true;

        try {
            Method t = Log.class.getDeclaredMethod("println_native",
                    int.class, int.class, String.class, String.class);
            t.setAccessible(true);

            Hooker hooker = new Hooker();
            Method cb = Hooker.class.getDeclaredMethod("callback", Object[].class);
            cb.setAccessible(true);

            Method backup = VNative.hookMethod(t, hooker, cb);
            if (backup == null) {
                sState = "hookMethod=null";
                return sState;
            }

            sTarget = t;
            sBackup = backup;
            sArmedAt = android.os.SystemClock.uptimeMillis();
            sArmed = true;

            // 自检：走一遍真实入口，看回调计数有没有涨。
            long before = sCalls;
            try {
                Log.i("HushLogTapProbe", "probe");
            } catch (Throwable ignored) {
            }
            boolean verified = sCalls > before && sArmed;
            sState = "ok verified=" + verified + " backup=" + backup.getName();

            if (!verified) {
                // 钩子挂上了但回调不响 —— 比没装更危险（会静默改变日志行为），立刻摘。
                disarm("verify failed");
                sState = "unverified, disarmed";
            }
            return sState;
        } catch (Throwable e) {
            sState = "failed " + e;
            return sState;
        }
    }

    private static void disarm(String why) {
        if (sDisarming) return;
        sDisarming = true;
        sArmed = false;
        Diag.log("lg", "logtap off: " + why + " calls=" + sCalls + " lines=" + sLines);

        final Method t = sTarget;
        if (t == null) return;
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean ok = VNative.unhookMethod(t);
                    Diag.log("lg", "logtap unhook=" + ok);
                } catch (Throwable e) {
                    Diag.log("lg", "logtap unhook failed " + e);
                }
            }
        }, "HushLogTapOff");
        th.setDaemon(true);
        th.start();
    }
}

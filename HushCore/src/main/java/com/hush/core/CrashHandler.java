package com.hush.core;

import com.hush.HushCore;

/**
 * 崩溃兜底 + 原始异常捕获。
 *
 * 1.6.26 的日志里，进程是这样死的：
 *
 * <pre>
 * 15:55:19.111  stack: main@[bound]
 *   com.tgc.sky.GameActivity.onCreateNative(Native Method)   ← Unity 已经在起来了
 * ...
 * 15:55:19.513  stack: main@[bound]
 *   com.netease.nis.quicklogin.f.b.interceptHandleException(CrashHandler.java:1)
 *   com.netease.nis.basesdk.crash.BaseJavaCrashHandler.uncaughtException(...:7)
 *   com.netease.nis.basesdk.crash.BaseJavaCrashHandler.uncaughtException(...:16) × 3343
 * 15:55:19.931  ... 同上 ...                                                   × 22089
 * 15:55:20.171  exit: SHUTDOWN hook fired
 * </pre>
 *
 * 两个结论：
 *
 * <ol>
 *   <li>确实有一个未捕获异常发生在 main 线程上；</li>
 *   <li>易盾的 crash handler 在**自己调用自己**，栈帧一路涨到两万多，
 *       最后大概是 StackOverflowError 收场，然后 System.exit(0)。
 *       所以日志里看不到任何有用的原始异常 —— 它被这层递归彻底盖住了。</li>
 * </ol>
 *
 * 这一版做两件事：
 *
 * <ol>
 *   <li><b>把第一个到达的 Throwable 原样写进 diag</b>，三条通道同时上，
 *       保证不论异常从哪条路进来都能抓到；</li>
 *   <li><b>切断 handler 互调成环</b>，这是我们自己也可能参与的环路
 *       （我们保存的"下游"完全可能就是易盾，而它保存的下游又是我们）。</li>
 * </ol>
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private volatile Thread.UncaughtExceptionHandler mDefaultHandler;

    /** 重入保护：同一条调用链上只处理一次，切断 handler 互调造成的死循环。 */
    private static final ThreadLocal<Boolean> IN_HANDLER = new ThreadLocal<Boolean>();

    /** 每个进程只详细打第一条，后面的只打一行，避免日志被刷爆。 */
    private static final java.util.concurrent.atomic.AtomicInteger COUNT =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final java.util.concurrent.atomic.AtomicInteger READOPT =
            new java.util.concurrent.atomic.AtomicInteger();

    private static volatile CrashHandler sInstance;
    private static volatile boolean sDeep;

    public static void create() {
        if (sInstance != null) return;
        new CrashHandler();
    }

    public CrashHandler() {
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        sInstance = this;
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    /**
     * 只在加固壳分支里调用。
     *
     * <p>普通 App 不该被我们插手：它们自己的崩溃上报组件会正常工作。
     * 加固壳则相反——网易 SDK / 易盾会反复覆盖 default handler，
     * 我们的观测点必须补回去，否则永远拿不到原始异常。</p>
     */
    public static void installDeep() {
        if (sDeep) return;
        sDeep = true;
        create();
        installMainThread();
        startWatchdog();
    }

    /**
     * 给 main 线程挂一个专属 handler。
     *
     * <p>三条通道里最可靠的一条：{@code Thread.dispatchUncaughtException}
     * 先取线程自己的 handler，取不到才用 default。挂在这里以后，
     * 不论 SDK 之后怎么改 default，异常都会先经过我们。</p>
     *
     * <p>原本的 handler（通常是易盾的）原样保留并在我们之后调用，
     * 行为与不加这一层一致，只多一行日志。</p>
     */
    private static void installMainThread() {
        try {
            Thread main = android.os.Looper.getMainLooper().getThread();
            if (main == null) {
                com.hush.utils.Diag.log("crash", "main thread null");
                return;
            }
            final Thread.UncaughtExceptionHandler origin = main.getUncaughtExceptionHandler();
            main.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    report("main", t, e);
                    if (origin == null) return;
                    try {
                        origin.uncaughtException(t, e);
                    } catch (Throwable ignored) {
                    }
                }
            });
            com.hush.utils.Diag.log("crash", "main handler installed origin="
                    + (origin == null ? "null" : origin.getClass().getName()));
        } catch (Throwable t) {
            com.hush.utils.Diag.log("crash", "main handler failed " + t);
        }
    }

    /**
     * SDK 会在自己的初始化里把 default handler 换掉，把我们的观测点挤出去。
     * 这里每秒补一次：只在「当前不是我们」时才重新挂，并把当时的那一个
     * 记成下游，保证它照旧被调用。
     */
    private static void startWatchdog() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        Thread.UncaughtExceptionHandler cur =
                                Thread.getDefaultUncaughtExceptionHandler();
                        if (cur == sInstance || sInstance == null) continue;
                        sInstance.mDownstream = cur;
                        Thread.setDefaultUncaughtExceptionHandler(sInstance);
                        if (READOPT.incrementAndGet() <= 3) {
                            com.hush.utils.Diag.log("crash", "default re-adopted "
                                    + (cur == null ? "null" : cur.getClass().getName()));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "HushCrashWatch");
        t.setDaemon(true);
        t.start();
    }

    /** 轮询时记下的"别人刚挂上的那个 handler"，我们插在它前面。 */
    private volatile Thread.UncaughtExceptionHandler mDownstream;

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        if (IN_HANDLER.get() != null) {
            // 已经在这条链上了，直接返回。这一句就是用来切断
            // 「易盾 → 我们 → 易盾 → 我们 …」这种环路，避免把栈撑爆。
            return;
        }
        IN_HANDLER.set(Boolean.TRUE);
        try {
            report("default", t, e);

            // 埋点/上报线程崩了不该带走整个进程。2.6.31 的日志里，
            // HMS 每次启动都会在 Hwid-Tracker-PoolN 上抛
            //   NoSuchMethodError: No static method e(ILjava/lang/String;LinkedHashMap;)V
            //   in class LW/b;   (W/b 来自 hwid/base.apk，和 commonkit 的
            //   Measurement 对不上版本)
            // 这是纯分析上报，跟登录流程无关，但它一路走到默认 handler，
            // ART 直接把进程杀掉 —— 界面看起来就是「进去两秒退回框架」。
            if (swallow(t, e)) {
                return;
            }

            try {
                if (HushCore.get() != null && HushCore.get().getExceptionHandler() != null) {
                    HushCore.get().getExceptionHandler().uncaughtException(t, e);
                }
            } catch (Throwable ignored) {
            }

            Thread.UncaughtExceptionHandler down =
                    mDownstream != null ? mDownstream : mDefaultHandler;
            if (down != null && down != this) {
                try {
                    down.uncaughtException(t, e);
                } catch (Throwable ignored) {
                }
            }
        } finally {
            IN_HANDLER.set(null);
        }
    }

    /**
     * 只吞「HMS 埋点/上报线程」上的链接期错误。
     *
     * <p>条件卡得很死，任一条不满足就照常往下传：</p>
     * <ul>
     *   <li>不是主线程 —— 主线程的异常必须让它死，否则 Looper 停在那里更糟；</li>
     *   <li>异常是 {@link LinkageError} 一类（方法/类/字段找不到）；</li>
     *   <li>栈里同时出现 huawei 和 Tracker / Measurement / HiAnalytics / commonkit。</li>
     * </ul>
     *
     * <p>注意不能用 {@code Diag.processName()} 判进程：那返回的是宿主名
     * （{@code com.hush.space:p10}），不是 guest 包名，永远不含 huawei。
     * 判据只能落在栈上。</p>
     */
    private static boolean swallow(Thread t, Throwable e) {
        try {
            if (t == null || e == null) return false;
            if ("main".equals(t.getName())) return false;
            if (!(e instanceof LinkageError)) return false;
            String s = format(e);
            if (s == null || s.indexOf("huawei") < 0) return false;
            if (s.indexOf("Tracker") < 0 && s.indexOf("Measurement") < 0
                    && s.indexOf("HiAnalytics") < 0 && s.indexOf("commonkit") < 0
                    && s.indexOf("support.log") < 0) {
                return false;
            }
            com.hush.utils.Diag.log("crash", "swallow 埋点异常 thread=" + t.getName()
                    + " " + e.getClass().getName() + ": " + e.getMessage());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ── 落盘 ────────────────────────────────────────────────────────────
    private static void report(String via, Thread t, Throwable e) {
        int n = COUNT.incrementAndGet();
        try {
            com.hush.utils.Diag.log("FATAL", "UNCAUGHT #" + n + " via=" + via
                    + " thread=" + (t == null ? "?" : t.getName() + "/" + t.getId())
                    + " proc=" + com.hush.utils.Diag.processName()
                    + "\n" + format(e));
        } catch (Throwable ignored) {
        }
        if (n == 1) {
            dumpLibs();
        }
    }

    /**
     * 手写而不是 {@code printStackTrace}：递归把栈撑爆那种情况下，
     * Throwable 可能带着两万多帧，整份打出来会把 diag 刷成几十 MB。
     */
    private static String format(Throwable e) {
        if (e == null) return "null";
        StringBuilder sb = new StringBuilder(2048);
        Throwable c = e;
        int level = 0;
        while (c != null && level < 5) {
            sb.append(level == 0 ? "" : "Caused by: ").append(c.getClass().getName());
            if (c.getMessage() != null) {
                sb.append(": ").append(c.getMessage());
            }
            sb.append('\n');

            StackTraceElement[] st = null;
            try {
                st = c.getStackTrace();
            } catch (Throwable ignored) {
            }
            if (st != null) {
                int lim = Math.min(st.length, 40);
                for (int i = 0; i < lim; ++i) {
                    sb.append("    at ").append(st[i]).append('\n');
                }
                if (st.length > lim) {
                    sb.append("    ... ").append(st.length - lim).append(" more\n");
                }
            }
            Throwable next = c.getCause();
            if (next == c) break;
            c = next;
            ++level;
        }
        return sb.toString();
    }

    /** 到崩溃这一刻为止，这个进程到底加载了哪些关键 so。 */
    private static void dumpLibs() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/maps"));
            java.util.LinkedHashSet<String> hit = new java.util.LinkedHashSet<String>();
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.contains(".so")) continue;
                int i = line.indexOf('/');
                if (i < 0) continue;
                String p = line.substring(i);
                String name = p.substring(p.lastIndexOf('/') + 1);
                if (name.contains("unity") || name.contains("il2cpp")
                        || name.contains("pharos") || name.contains("unisec")
                        || name.contains("Bootloader") || name.contains("androidmainruns")
                        || name.contains("monochrome") || name.contains("webviewchromium")
                        || name.contains("HushCore") || name.contains("libmain")) {
                    hit.add(name);
                }
            }
            r.close();
            com.hush.utils.Diag.log("crash", "libs@" + hit.size() + " " + hit);
        } catch (Throwable t) {
            com.hush.utils.Diag.log("crash", "libs failed " + t);
        }
    }
}

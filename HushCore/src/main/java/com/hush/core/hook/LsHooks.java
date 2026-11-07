package com.hush.core.hook;

import com.hush.core.VNative;
import com.hush.utils.Diag;

import java.lang.reflect.Method;

/**
 * 第一批 ART 层 hook。
 *
 * 这一版的目的不是"修好光遇"，而是**验证 HushHook 真的在这个 ROM 上工作**：
 * init 成功、hook 生效、backup 能回调。只要 PushGuard 打出日志，就说明
 * 整条链路通了，后面才值得往上叠 SignCompat 这些真正的业务 hook。
 */
public final class LsHooks {

    private static boolean sInstalled = false;

    private LsHooks() {
    }

    /**
     * @param shellGuest 这个 guest 是不是加固壳。false 时一个 hook 都不装，
     *                   避免把 killProcess / getPackageInfo / checkSignatures
     *                   这类全局方法的返回值改坏，连累普通 App。
     */
    public static synchronized void install(boolean shellGuest) {
        if (sInstalled) return;
        sInstalled = true;

        if (!shellGuest) {
            // 普通 App 没有任何校验需要绕过。HushHook 的 Init 已经在 enableIO 里
            // 后台跑着了，不阻塞主线程、也不装任何 Java 层 hook。
            Diag.log("hook", "skip: not a shell guest, no ART hooks installed");
            return;
        }

        // 最先装 JNI 函数表的前置校验：壳在 GetMethodID 里传非法字符串指针时
        // ART 会直接在 strlen 里崩，连栈都留不下。这一层先把它拦下来并记录
        // 「谁、用什么参数调的」，然后再继续装 ART hook。
        try {
            Diag.log("jni", "envGuard=" + VNative.installJniEnvGuard());
        } catch (Throwable t) {
            Diag.log("jni", "envGuard failed " + t);
        }

        // 崩溃捕获：必须在任何 guest 代码跑起来之前装好。
        // 1.6.26 的日志里进程是被「未捕获异常 + 易盾 handler 自身递归」弄死的，
        // 原始异常被两万多层递归盖住，一条都看不见。这里三条通道同时上，
        // 只为了把第一个 Throwable 原样写进 diag。
        try {
            com.hush.core.CrashHandler.installDeep();
        } catch (Throwable t) {
            Diag.log("crash", "installDeep failed " + t);
        }

        // HushHook 的 Init 在后台线程跑（主线程在 native 里等它会死锁）。
        // 这里在主线程上用 Thread.sleep 轮询：sleep 是 Java 状态，ART 的
        // SuspendAll 才能把主线程挂起来，Init 才有机会走完。
        VNative.startHookInit();
        long deadline = android.os.SystemClock.uptimeMillis() + 6000L;
        while (!VNative.isHookReady() && !VNative.isHookFailed()
                && android.os.SystemClock.uptimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (!VNative.isHookReady()) {
            Diag.log("hook", "HushHook not ready after wait, skip hooks");
            return;
        }
        Diag.log("hook", "HushHook ready");

        // 观测层全部改成「零 hook」的被动快照。
        //
        // 之前三版为了拿数据，分别 hook 了 Activity 生命周期、startActivity
        // 全重载、WebView.loadUrl、以及 EGL 的五个函数。结果是：日志里一条
        // 数据都没多出来（callback 从未被调到），而 ProtocolLauncher 从
        // 1.6.20 起再也不跳转 —— 探针本身就极可能是卡住的原因。
        // 这一版一条 guest hook 都不装，只反射读 ActivityThread.mActivities
        // 把 decorView 树打出来，用数据说话。
        try {
            ViewDump.install();
        } catch (Throwable t) {
            Diag.log("vd", "install failed " + t);
        }

        // guest 自己的日志：SDK 卡在哪一步只写在它自己的 Log 里。
        //
        // 1.6.30 试过 hook android.util.Log.println_native，结果每个 guest 进程
        // 在 hook 完成后 9ms 就 _exit(0)（42 轮重启，全部同一点）—— 这条钩子
        // 在易盾眼皮底下活不过一次调用。已彻底撤掉，回到纯读的 logcat 通道。
        // 日志本来就是可选项：拿不到就靠 bypass 的回看判断，不拿进程去赌。
        try {
            GuestLog.start();
        } catch (Throwable t) {
            Diag.log("lg", "start failed " + t);
        }

        // guest 自己的 shared_prefs / assets 状态：协议是否已同意、正文能不能读到。
        try {
            GuestState.start("com.netease.sky");
        } catch (Throwable t) {
            Diag.log("st", "start failed " + t);
        }

        // WebView 探针：1.6.31 证明协议页是死在 WebView 初始化（PackageInfo 为
        // null → 读 versionCode NPE），而不是「不建 WebView」。这里在创建之前
        // 把框架缓存 / guest PM / 带 flags 的查询三条口径都记下来，用来判断
        // 是框架静态缓存丢了，还是容器 PM 把查询吞成了 null。
        try {
            WebViewProbe.start("com.netease.sky");
        } catch (Throwable t) {
            Diag.log("wv", "start failed " + t);
        }

        // 协议页正文注入 + 空转兜底：**已按用户要求全部停用**，协议页恢复官方原样。
        //
        // 1.6.34/1.6.35 的做法是：容器自己拉官方 HTML，用原生 TextView 盖在
        // WebView 的位置上。正文确实渲染出来了，但两次都没能稳定留在界面上：
        //   - 1.6.34 挂 WebView 的父容器，SDK 换 WebView 时被连带清掉（闪一下就没）；
        //   - 1.6.35 改挂 DecorView 躲开重建，结果落到比弹窗更低的窗口层级，
        //     正文被压在弹窗下面（截图里能看到文字在卡片背后，宽度还溢出）。
        // 这条路要真正做对，得把 View 加到弹窗自己的 Window 里，改动面比收益大，
        // 而且协议页本来就是一次性弹窗 —— 用户点「接受」即可，不影响进游戏。
        //
        // 所以这里不再注入任何 View，也不自动替用户跳转。ProtocolBypass 和
        // ProtocolText 的代码保留（带说明），需要时把下面一行恢复即可。
        //
        // try {
        //     ProtocolBypass.start();
        // } catch (Throwable t) {
        //     Diag.log("bypass", "start failed " + t);
        // }
        Diag.log("bypass", "disabled: protocol page left as-is (official)");

        // jmethodID 形态探针：Android 14 起 ART 可能把 jmethodID 编码成 uint64
        // 而不是 ArtMethod*。加固壳若把它当指针用就会读到垃圾。先只报告。
        boolean indirect = false;
        try {
            Method probe = android.os.Process.class.getDeclaredMethod("killProcess", int.class);
            String r = VNative.probeJniId(probe);
            Diag.log("jni", r);
            indirect = r != null && r.contains("looksPtr=0");
        } catch (Throwable t) {
            Diag.log("jni", "probe failed " + t);
        }

        // 和拾光同一条兼容路径：ART 把 jmethodID 编码成索引时，让
        // JniIdManager::EncodeGenericId 直接返回 ArtMethod*，恢复旧壳期待的语义。
        // 只在「探针确认是编码值」时才装 —— 指针模式下装它反而会把 JNI 打崩。
        if (indirect && android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                Diag.log("jni", "EncodeGenericId hook -> " + VNative.installJniIdCompat());
            } catch (Throwable t) {
                Diag.log("jni", "EncodeGenericId hook failed " + t);
            }
        }

        Diag.log("hook", "killProcess=" + hookKillProcess());
        Diag.log("hook", "load0=" + hookLoad0());
        // SignCompat：壳在 native 里直接打宿主的 PackageManager 查签名，
        // 查回来是宿主证书，一比就不一致。这一层把结果换回 guest 的原版签名。
        Diag.log("hook", "signature=" + SignatureCompat.install());
        // Runtime.exit 不动：容器自身退出也走这条路，拦了会关不掉 App。
        // Diag.log("hook", "exit=" + hookExit());
    }

    // ── 自杀拦截 ────────────────────────────────────────────────────────
    // 加固壳在校验失败后常见的收尾动作是 Process.killProcess(myPid()) 或
    // System.exit()。这两个一旦放过去，进程就没了，前面所有日志都白打。
    public static final class KillProcessHooker {
        public Method backup;

        public Object callback(Object[] args) {
            int pid = -1;
            try {
                pid = (Integer) args[0];
            } catch (Throwable ignored) {
            }
            if (pid <= 0 || pid == android.os.Process.myPid()) {
                Diag.log("hook", "killProcess(" + pid + ") blocked");
                return null;
            }
            try {
                return HookInvoke.call(backup, args);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    public static final class ExitHooker {
        public Method backup;

        public Object callback(Object[] args) {
            int code = -1;
            try {
                code = (Integer) args[0];
            } catch (Throwable ignored) {
            }
            Diag.log("hook", "exit(" + code + ") blocked");
            return null;
        }
    }

    private static boolean hookKillProcess() {
        try {
            Method target = android.os.Process.class.getDeclaredMethod("killProcess", int.class);
            KillProcessHooker hooker = new KillProcessHooker();
            Method cb = KillProcessHooker.class.getDeclaredMethod("callback", Object[].class);
            Method backup = VNative.hookMethod(target, hooker, cb);
            if (backup == null) return false;
            hooker.backup = backup;
            return true;
        } catch (Throwable t) {
            Diag.log("hook", "hookKillProcess failed " + t);
            return false;
        }
    }

    /** 2.6.53：主进程自杀拦截。只拦 pid == myPid，别的进程照常能杀。 */
    public static boolean installSelfKillGuard() {
        return hookKillProcess();
    }

    // ── so 加载路径兜底（拾光的 SoLoadCompat）────────────────────────────
    // System.load("/data/app/.../libxxx.so") 走 Runtime.load0(Class, String)。
    // 容器里这个绝对路径要么是宿主的、要么是壳自己拼的，在沙盒里根本不存在，
    // 于是 dlopen 失败、壳走错误分支。这里只在「原路径确实打不开」时才改写成
    // guest 的 nativeLibraryDir 下的同名 so，其余一律原样透传。
    public static final class Load0Hooker {
        public Method backup;

        public Object callback(Object[] args) {
            String path = null;
            try {
                path = (String) args[1];
            } catch (Throwable ignored) {
            }
            if (path != null && !path.isEmpty()) {
                try {
                    java.io.File f = new java.io.File(path);
                    if (f.isAbsolute() && !f.exists()) {
                        String fixed = VNative.resolveLibPath(path);
                        if (fixed != null && !fixed.equals(path)) {
                            Diag.log("hook", "load0 rewrite " + path + " -> " + fixed);
                            Object[] nargs = new Object[]{args[0], fixed};
                            return HookInvoke.call(backup, nargs);
                        }
                        Diag.log("hook", "load0 miss " + path);
                    }
                } catch (Throwable ignored) {
                }
            }
            try {
                return HookInvoke.call(backup, args);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static boolean hookLoad0() {
        try {
            Method target = java.lang.Runtime.class.getDeclaredMethod(
                    "load0", Class.class, String.class);
            target.setAccessible(true);
            Load0Hooker hooker = new Load0Hooker();
            Method cb = Load0Hooker.class.getDeclaredMethod("callback", Object[].class);
            Method backup = VNative.hookMethod(target, hooker, cb);
            if (backup == null) return false;
            hooker.backup = backup;
            return true;
        } catch (Throwable t) {
            Diag.log("hook", "hookLoad0 failed " + t);
            return false;
        }
    }

    private static boolean hookExit() {
        try {
            Method target = java.lang.Runtime.class.getDeclaredMethod("exit", int.class);
            ExitHooker hooker = new ExitHooker();
            Method cb = ExitHooker.class.getDeclaredMethod("callback", Object[].class);
            Method backup = VNative.hookMethod(target, hooker, cb);
            if (backup == null) return false;
            hooker.backup = backup;
            return true;
        } catch (Throwable t) {
            Diag.log("hook", "hookExit failed " + t);
            return false;
        }
    }
}

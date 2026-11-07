package com.hush.core;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import androidx.annotation.Keep;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.hush.HushCore;
import com.hush.app.BActivityThread;
import com.hush.utils.compat.DexFileCompat;

public class VNative {

    public static final String TAG = "VNative";
    private static volatile boolean sCoreLoaded = false;
    private static volatile boolean sTargetLoaded = false;
    private static final Object sLock = new Object();
    public static String libtarget = "libbgmi.so";

    // ── Native library loading ────────────────────────────────────────────
    static {
        try {
            Context ctx = HushCore.getContext();
            // libHushCore.so 现在依赖 libhushhook.so（ART hook 引擎）和
            // libc++_shared.so。这两者必须先加载进进程，否则 libHushCore
            // 的 DT_NEEDED 解析不到，System.load 会直接失败。
            try {
                System.loadLibrary("c++_shared");
            } catch (Throwable ignored) {
            }
            try {
                System.loadLibrary("hushhook");
            } catch (Throwable ignored) {
            }
            File coreLib = new File(
                ctx.getApplicationInfo().nativeLibraryDir,
                "libHushCore.so"
            );
            if (coreLib.exists()) {
                System.load(coreLib.getAbsolutePath());
            } else {
                System.loadLibrary("HushCore");
            }
            sCoreLoaded = true;

            // If detection features are in a separate HASAD lib, add:
            // System.loadLibrary("HASAD");   // or the full path

            // Load target lib silently (original behaviour)
            loadTargetSilently(ctx);
        } catch (Throwable t) {
            // silent
        }
    }

    private static void loadTargetSilently(Context ctx) {
        if (sTargetLoaded) return;
        synchronized (sLock) {
            if (sTargetLoaded) return;
            try {
                File targetLib = new File(ctx.getFilesDir(), "loader/" + libtarget);
                if (!targetLib.exists()) return;

                File optDir = new File(ctx.getFilesDir(), "loader/.opt");
                if (!optDir.exists()) optDir.mkdirs();

                ClassLoader parent = VNative.class.getClassLoader();
                Class<?> baseDexCL = Class.forName("dalvik.system.BaseDexClassLoader");
                Class<?> dexPathListClass = Class.forName("dalvik.system.DexPathList");

                Field pathListField = baseDexCL.getDeclaredField("pathList");
                pathListField.setAccessible(true);
                Object pathList = pathListField.get(parent);

                Field nativeLibDirsField = dexPathListClass.getDeclaredField("nativeLibraryDirectories");
                nativeLibDirsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<File> nativeLibDirs = (List<File>) nativeLibDirsField.get(pathList);

                List<File> newDirs = new ArrayList<>();
                newDirs.add(targetLib.getParentFile());
                newDirs.addAll(nativeLibDirs);
                nativeLibDirsField.set(pathList, newDirs);

                // Patch nativeLibraryPathElements for API 23+
                try {
                    Field elementsField = dexPathListClass.getDeclaredField("nativeLibraryPathElements");
                    elementsField.setAccessible(true);
                    Method makeElements;
                    try {
                        makeElements = dexPathListClass.getDeclaredMethod("makePathElements", List.class);
                    } catch (NoSuchMethodException e) {
                        makeElements = dexPathListClass.getDeclaredMethod("makePathElements", List.class, File.class, List.class);
                    }
                    makeElements.setAccessible(true);
                    Object[] newElements;
                    try {
                        newElements = (Object[]) makeElements.invoke(null, newDirs);
                    } catch (Exception e) {
                        newElements = (Object[]) makeElements.invoke(null, newDirs, ctx.getFilesDir(), new ArrayList<>());
                    }
                    elementsField.set(pathList, newElements);
                } catch (Throwable ignored) {}

                System.loadLibrary(libtarget.replace("lib", "").replace(".so", ""));
                sTargetLoaded = true;
            } catch (Throwable t) {}
        }
    }

    // ── Original JNI declarations (HushCore) ─────────────────────────────
    public static native void init(int apiLevel);
    public static native void enableIO();
    public static native void addIORule(String targetPath, String relocatePath);
    public static native void hideXposed();
    public static native void setHostPkg(String pkg);

    /** native 层的 libc hook 是否挂上了（open/openat/fopen/stat 等）。 */
    public static native boolean isNativeIoActive();

    /** 从 native 侧发一次真实 open，返回 0 表示成功，负数表示 -errno。 */
    public static native int probeOpen(String path);

    // ── ART 层 Java 方法 hook（HushHook）──────────────────────────────────
    /** 把 target 替换成 hooker.callback，返回 backup 方法（失败为 null）。
     *  target 可以是 Method，也可以是 Constructor（JNI 的 FromReflectedMethod
     *  两者都接受，HushHook 内部按 <init> 处理）。 */
    public static native java.lang.reflect.Method hookMethod(
            Object target, Object hooker, java.lang.reflect.Method callback);

    /** HushHook 是否初始化成功。 */
    public static native boolean isHookReady();

    /** 初始化已明确失败（区别于"还在跑"），等待循环可以提前结束。 */
    public static native boolean isHookFailed();

    /** 触发 HushHook 异步初始化（幂等）。 */
    public static native void startHookInit();

    /**
     * ART 层 hook 总闸。默认关：普通 App 连 HushHook 的 Init 都不会跑。
     * 只有 HookPolicy 判定为白名单壳时，才在 enableIO 之前打开。
     */
    public static native void setHookGate(boolean on);

    public static native boolean unhookMethod(java.lang.reflect.Method target);

    /**
     * 报告一个方法在 ART 里的 jmethodID 形态，以及 libart 是否导出 JniIdManager 相关符号。
     * 返回形如 {@code mid=0x7f... looksPtr=1 encM=... decM=...}。
     */
    public static native String probeJniId(java.lang.reflect.Method target);

    /**
     * 在 JNI 函数表上装一层 GetMethodID / GetStaticMethodID / GetFieldID 的前置校验。
     * 壳传进来非法字符串指针时拦下来并记录调用者，避免 ART 在 strlen 里猝死。
     */
    public static native String installJniEnvGuard();

    /**
     * EGL / ANativeWindow 观测。旋转后黑屏时用它区分
     * 「窗口没 resize」「EGLSurface 没重建」「渲染线程停了」。
     */
    public static native boolean installSurfaceProbe();

    /**
     * 让 libart 的 {@code JniIdManager::EncodeGenericId<ArtMethod>} 直接返回 ArtMethod*，
     * 把「jmethodID 是索引」退回成「jmethodID 是指针」。
     *
     * 拾光 5.3.91 用的就是这一条（libhexin.so 里 dlsym 的符号串一字不差）。
     * 返回形如 {@code ok addr=0x... ret=0}，ret 是 DobbyHook 的返回值。
     */
    public static native String installJniIdCompat();

    /** native IO hook 的详细状态（各符号是否挂上 + 指定路径的改写结果）。 */
    public static native String nativeIoDiag(String path);

    /** 把 diag.log 绝对路径交给 native，退出追踪直接往里写。 */
    public static native void setDiagPath(String path);
    public static native void nukeTargetLibrary(String libName, int layers);

    /**
     * 设置本进程的 dumpable 标志，返回设置前的值。
     *
     * <p>容器里 guest 进程默认是 non-dumpable，同 uid 的其他进程在
     * {@code /proc} 里看不到它（hidepid + ptrace_may_access）。
     * Star 的 native 注入器正是靠扫 {@code /proc} 找游戏的，看不到就只能报
     * 「没找到光遇进程」。把它改回 1，同 uid 的注入器才能看见。
     */
    public static native int setDumpable(int value);

    /** 读 prctl(PR_GET_DUMPABLE)。 */
    public static native int getDumpable();

    // Nuke constants
    public static final int NUKE_L4_ELF_DESTROY = 1 << 3;



    // ── High‑level init combining host info + anti‑detection + IO rules ────
    public static void initDetectionAndNuke(int apiLevel) {
        // 1. Original host package info
        initHostInfo();

        // 2. Anti‑cheat nuke (matching NativeCore)
        try {
            // nukeTargetLibrary("libanogs", NUKE_L4_ELF_DESTROY);
            // nukeTargetLibrary("libanort", NUKE_L4_ELF_DESTROY);
            // Log.i(TAG, "NUKE sent for libanogs");
        } catch (Throwable t) {
            Log.e(TAG, "NUKE init failed", t);
        }

        // 3. Fix Vivo Android 16 VSPA frame drops by sinking the proc file to /dev/null
        try {
            addIORule("/proc/vivo_rsc/frame_set_priority", "/dev/null");
        } catch (Throwable t) {
            Log.e(TAG, "VSPA IO rule failed", t);
        }

        // 4. Redirect host data dir to guest
        applyGuestHostDataRedirect();
    }

    /**
     * Redirect legacy host data paths to the guest's private directory
     * so that games don't see the host's data.
     */
    private static void applyGuestHostDataRedirect() {
        try {
            String guestPkg = BActivityThread.getAppPackageName();
            if (guestPkg == null || guestPkg.isEmpty()) return;
            Context ctx = HushCore.getContext();
            if (ctx == null) return;
            String hostPkg = HushCore.getHostPkg();
            android.content.pm.ApplicationInfo guestInfo =
                    HushCore.getBPackageManager()
                            .getApplicationInfo(guestPkg, 0, BActivityThread.getUserId());
            if (guestInfo == null || guestInfo.dataDir == null) return;
            String guestData = guestInfo.dataDir;
            addIORule("/data/user/0/" + hostPkg, guestData);
            addIORule("/data/data/" + hostPkg, guestData);
            if (!"com.pubgm".equals(hostPkg)) {
                addIORule("/data/user/0/com.pubgm", guestData);
                addIORule("/data/data/com.pubgm", guestData);
            }
            Log.i(TAG, "IO guest data redirect " + hostPkg + " -> " + guestData);
        } catch (Exception e) {
            Log.e(TAG, "IO rule failed", e);
        }
    }

    // ── Original helpers ──────────────────────────────────────────────────
    public static void initHostInfo() {
        try {
            String hostPkg = HushCore.getHostPkg();
            if (hostPkg != null) {
                setHostPkg(hostPkg);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to set host pkg in native layer", e);
        }
    }

    @Keep
    public static int getCallingUid(int origCallingUid) {
        if (origCallingUid > 0 && origCallingUid < Process.FIRST_APPLICATION_UID) return origCallingUid;
        if (origCallingUid > Process.LAST_APPLICATION_UID) return origCallingUid;
        if (origCallingUid == HushCore.getHostUid()) {
            // GMS / GSF packages: return host UID so Binder identity is consistent
            // across all Android versions (returning ROOT_UID breaks newer GMS modules).
            String appPkg = BActivityThread.getAppPackageName();
            if ("com.google.android.gms".equals(appPkg)
                    || "com.google.android.gsf".equals(appPkg)
                    || "com.google.process.gservices".equals(appPkg)
                    || "com.google.android.gms.persistent".equals(appPkg)) {
                return origCallingUid;  // pass through real host UID
            }
            if ("com.google.android.webview".equals(appPkg)) {
                return Process.myUid();
            }

            // AttributionSource 相关校验必须返回真实 uid。
            //
            // ContextCompat.fix 会把 app Context 的 attribution uid 设成宿主真实
            // uid（见那边的注释），而 Android 12+ 的
            // AttributionSource.enforceCallingUid() 就是拿它和
            // Binder.getCallingUid() 比对。这里若返回虚拟 uid，两边又对不上，
            // 会变成反方向的 "10666 doesn't match source uid: 10442"。
            if (android.os.Binder.getCallingPid() != android.os.Process.myPid()) {
                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                int limit = Math.min(stack.length, 8);
                for (int i = 2; i < limit; i++) {
                    if (stack[i].getClassName().contains("AttributionSource")) {
                        return origCallingUid;
                    }
                }
            }

            return BActivityThread.getCallingBUid();
        }
        return origCallingUid;
    }

    @Keep
    public static String redirectPath(String path) {
        return VCore.get().redirectPath(path);
    }

    @Keep
    public static File redirectPath(File path) {
        return VCore.get().redirectPath(path);
    }

    /**
     * 给 native 侧的 so 加载兜底用：把一个 so 名（libxxx.so 或 xxx）解析成
     * guest 沙盒里的绝对路径。找不到返回 null。
     *
     * Runtime.nativeLoad 一旦返回错误串，壳就会走异常分支；这条分支正是历史上
     * 0xfffff9 的产地之一。所以失败时要能把路径找回来。
     */
    @Keep
    public static String resolveLibPath(String soName) {
        if (soName == null) return null;
        String name = soName;
        if (name.indexOf('/') >= 0) {
            name = name.substring(name.lastIndexOf('/') + 1);
        }
        if (name.isEmpty()) return null;
        if (!name.startsWith("lib")) name = "lib" + name;
        if (!name.endsWith(".so")) name = name + ".so";

        try {
            String pkg = BActivityThread.getAppPackageName();
            if (pkg == null || pkg.isEmpty()) return null;
            android.content.pm.ApplicationInfo ai =
                    HushCore.getBPackageManager().getApplicationInfo(pkg, 0, BActivityThread.getUserId());
            if (ai == null || ai.nativeLibraryDir == null) return null;
            File f = new File(ai.nativeLibraryDir, name);
            if (f.exists()) return f.getAbsolutePath();

            // 有些包把 so 摊在 dataDir/lib 下，再兜一层
            if (ai.dataDir != null) {
                File alt = new File(ai.dataDir, "lib/" + name);
                if (alt.exists()) return alt.getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

}
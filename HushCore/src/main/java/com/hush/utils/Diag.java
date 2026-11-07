package com.hush.utils;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 引擎侧诊断日志。
 *
 * 关键设计：日志文件句柄在 init() 时一次性打开并长期持有。
 * 原因：IO 重定向一旦生效（VNative.enableIO 之后），任何走 File API 的
 * 路径都可能被改写到 guest 沙盒里；如果每行日志都重新 open 一次文件，
 * 重定向生效后的日志会全部丢失，看起来就像"进程死在了那一行"。
 * 预先打开的 fd 不受重定向影响，能一直写到进程真正结束。
 */
public final class Diag {

    private static final String TAG = "HushDiag";
    private static final String FILE_NAME = "diag.log";

    private static volatile File sFile;
    private static volatile FileOutputStream sOut;
    /** 2.6.55：实际使用的文件名（diag.log 打不开时会退到 diag2.log / diag3.log）。 */
    private static volatile String sPrimaryName = FILE_NAME;

    /**
     * 镜像文件：{@code /sdcard/Download/HushSpace/diag.log}。
     *
     * <p>主日志在 {@code Android/data/com.hush.space/files} 下，卸载重装后这个
     * 目录有时会残留一份属主不对的旧文件（华为/荣耀的 ROM 卸载不清理
     * Android/data），{@code new FileOutputStream(f, true)} 直接抛异常，
     * 日志就悄悄落到内部存储里，用户怎么导都是那份旧的。</p>
     *
     * <p>所以再开一路到 Download，走公共目录，任何文件管理器都能翻到，
     * 而且每次启动先删到 8MB 以内，保证拿到的不会是一年前的。</p>
     */
    private static volatile File sMirror;
    private static volatile FileOutputStream sOut2;
    private static volatile boolean sReady;
    private static volatile String sProcName = "?";
    private static volatile String sPrefix = "";

    private Diag() {
    }

    public static synchronized void init(Context context) {
        if (sReady) return;
        if (context == null) {
            // guest 进程里 HushCore.getContext() 在 bind 早期可能还是 null，
            // 以前这里直接 return，结果整个进程一行日志都没有 —— 2.6.15
            // 之后拿回来的 diag.log 一直停在旧版本就是这个问题。
            context = hostContext();
            if (context == null) return;
        }
        sReady = true;
        sProcName = readProcessName();
        sPrefix = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + " [" + sProcName + "] ";
        sFile = resolve(context);
        // 不再「启动即清空」。
        //
        // 1.6.34 那次拿回来的 diag.log 只有 572 字节：一个空的 crash buffer 段，
        // 连第一行 boot 都没有。原因是主进程、p0、p1 共用一个文件，谁先 init
        // 谁就把别人的内容截断；只要截断之后那一行没写成（进程当场死了），
        // 整份日志就归零，事后完全无法复盘。
        //
        // 改成按体积轮转：超过 4MB 才把旧的留成 diag.log.prev，然后从空开始。
        // 这样任何一次异常退出最多丢当前这一轮，不会把历史一起抹掉。
        if (sFile != null) {
            try {
                // 阈值从 4MB 提到 16MB：4MB 那档在手机上两三天就到，
                // 轮转成 diag.log.prev 之后用户只拷 diag.log，
                // 等于拿到的是一份刚开头的空文件，看着就像「日志没写」。
                if (sFile.length() > 16L * 1024 * 1024) {
                    File prev = new File(sFile.getParentFile(), FILE_NAME + ".prev");
                    if (prev.exists()) prev.delete();
                    sFile.renameTo(prev);
                }
            } catch (Throwable ignored) {
            }
        }
        if (sFile != null) {
            try {
                // 卸载重装后 Android/data 可能残留一份属主不对的旧 diag.log，
                // 直接 open 会抛 EACCES，日志就悄悄写去内部存储了。
                ensureWritable(sFile);
                sOut = new FileOutputStream(sFile, true);
            } catch (Throwable t) {
                sOut = null;
                // 打不开就删掉重建 —— 宁可丢掉历史，也不能没有新日志
                try {
                    if (sFile.exists() && sFile.delete()) {
                        sFile.createNewFile();
                        sOut = new FileOutputStream(sFile, true);
                    }
                } catch (Throwable ignored) {
                }
            }
            // 默认 0600，第三方文件管理器打开会报「私有权限」。
            // 日志本身不含敏感数据，放宽成 0666 方便用户直接查看。
            try {
                android.system.Os.chmod(sFile.getAbsolutePath(), 0666);
            } catch (Throwable ignored) {
            }
        }
        // 第二路：Download 公共目录，卸载重装也翻得到
        sMirror = resolveMirror();
        if (sMirror != null) {
            try {
                sOut2 = new FileOutputStream(sMirror, true);
            } catch (Throwable ignored) {
                sOut2 = null;
            }
        }
        String ver = "?";
        try {
            android.content.pm.PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            ver = pi.versionName + "(" + pi.versionCode + ")";
        } catch (Throwable ignored) {
        }
        // 这一行同时是「日志通道自检」：out=NULL 表示常驻 fd 没打开，
        // len 是写入前的文件长度，path 用来确认到底落在 external 还是 internal。
        // 1.6.34 那份空日志缺的就是这三项，无法判断是没写还是写到别处去了。
        log("boot", "process=" + sProcName
                + " pid=" + Process.myPid()
                + " ver=" + ver
                + " sdk=" + Build.VERSION.SDK_INT
                + " abi=" + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?")
                + " out=" + (sOut == null ? "NULL" : "ok")
                + " len=" + (sFile == null ? -1 : sFile.length())
                + " mirror=" + (sOut2 == null ? "NULL" : "ok")
                + " path=" + (sFile == null ? "null" : sFile.getAbsolutePath()));
    }

    public static File file() {
        return sFile;
    }

    /** 公共目录里的那份，用户在任何文件管理器里都能直接打开。 */
    public static File mirrorFile() {
        return sMirror;
    }

    private static void ensureWritable(File f) {
        try {
            if (f.exists() && !f.canWrite()) {
                f.setWritable(true, false);
                if (!f.canWrite()) {
                    f.delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static File resolveMirror() {
        try {
            File root = android.os.Environment.getExternalStorageDirectory();
            File dir = new File(root, "Download/HushSpace");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File f = new File(dir, FILE_NAME);
            // 镜像只保留最近的一轮，超过 8MB 直接清掉
            if (f.exists() && f.length() > 8L * 1024 * 1024) {
                f.delete();
            }
            ensureWritable(f);
            if (f.exists() && !f.canWrite()) f.delete();
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    public static File resolve(Context context) {
        // 所有进程（宿主 / HMS / 账号 kit）统一写宿主的 external 目录。
        //
        // guest 进程 context.getExternalFilesDir() 给的是它自己那个包
        // （Android/data/com.huawei.hwid/files），导日志的人根本不会去翻；
        // 而且一旦 IO 重定向生效，这个路径还会被改写到别的沙盒里。
        // 固定用宿主包名拼路径，用户只需要拿
        // Android/data/com.hush.space/files/diag.log 这一份。
        try {
            String host = hostPackage();
            if (host != null && !host.isEmpty()) {
                File root = android.os.Environment.getExternalStorageDirectory();
                File f = new File(root, "Android/data/" + host + "/files/" + FILE_NAME);
                File dir = f.getParentFile();
                if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                    // 关键：重装后残留的旧 diag.log 属主不对，直接 open 会 EACCES，
                    // 整个 resolve 就 fallback 到内部存储了 —— 用户永远看不到新日志。
                    // 先修可写，修不了就删，删了还不行就换一个文件名。
                    // 2.6.55：之前只要 diag.log 打不开（旧文件属主/权限问题），
                    // 整个 resolve 就 fallback 到内部存储，用户从 Android/data
                    // 里拷到的永远是那份几小时前的旧文件——看起来就是「日志没写」。
                    ensureWritable(f);
                    for (String name : new String[]{FILE_NAME, "diag2.log", "diag3.log"}) {
                        File target = new File(dir, name);
                        try {
                            FileOutputStream w = new FileOutputStream(target, true);
                            w.write("".getBytes(StandardCharsets.UTF_8));
                            w.close();
                            sPrimaryName = name;
                            return target;
                        } catch (Throwable t) {
                            if (target.exists()) target.delete();
                        }
                    }
                    return f;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, FILE_NAME);
                ensureWritable(f);
                if (f.exists() && !f.canWrite()) f.delete();
                FileOutputStream w = new FileOutputStream(f, true);
                w.write("".getBytes(StandardCharsets.UTF_8));
                w.close();
                return f;
            }
        } catch (Throwable ignored) {
        }
        try {
            File dir = new File(context.getFilesDir(), "diag");
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, FILE_NAME);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 宿主包名。引擎在 utils 包，不能直接引用 HushCore，走反射。 */
    private static String hostPackage() {
        try {
            Class<?> c = Class.forName("com.hush.HushCore");
            Object v = c.getMethod("getHostPkg").invoke(null);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Context hostContext() {
        try {
            Class<?> c = Class.forName("com.hush.HushCore");
            Object v = c.getMethod("getContext").invoke(null);
            return v instanceof Context ? (Context) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void log(String tag, String msg) {
        Log.i(TAG, "[" + tag + "] " + msg);
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " [" + sProcName + "] " + tag + ": " + msg + "\n";
        byte[] data = line.getBytes(StandardCharsets.UTF_8);

        // 镜像先写：主日志因为权限问题写不进去时，至少 Download 里那份是全的
        FileOutputStream out2 = sOut2;
        if (out2 != null) {
            try {
                out2.write(data);
                out2.flush();
            } catch (Throwable t) {
                sOut2 = null;
            }
        }

        FileOutputStream out = sOut;
        if (out != null) {
            try {
                out.write(data);
                out.flush();
                return;
            } catch (Throwable t) {
                // fd 失效（被回收 / 目录被删），退回到重新打开
                sOut = null;
            }
        }
        File f = sFile;
        if (f == null) return;
        try {
            FileOutputStream w = new FileOutputStream(f, true);
            w.write(data);
            w.close();
            sOut = new FileOutputStream(f, true);
        } catch (Throwable ignored) {
        }
    }

    public static String stack(Throwable t) {
        if (t == null) return "null";
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    public static String processName() {
        return sProcName;
    }

    private static String readProcessName() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/cmdline"));
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = r.read()) > 0) sb.append((char) c);
            r.close();
            if (sb.length() > 0) return sb.toString();
        } catch (Throwable ignored) {
        }
        return "?";
    }
}

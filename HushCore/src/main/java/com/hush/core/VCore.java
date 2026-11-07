package com.hush.core;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.hush.HushCore;
import com.hush.activation.SdkBridge;
import com.hush.app.BActivityThread;
import com.hush.core.env.BEnvironment;
import com.hush.utils.Diag;
import com.hush.utils.FileUtils;
import com.hush.utils.TrieTree;

@SuppressLint("SdCardPath")
public class VCore {
    public static final String TAG = "VCore";

    private static final VCore sVCore = new VCore();
    private static final TrieTree mTrieTree = new TrieTree();
    private static final TrieTree sBlackTree = new TrieTree();
    private final Map<String, String> mRedirectMap = new LinkedHashMap<>();

    /**
     * 重定向总开关。
     * 构建规则期间必须关闭：addRedirect 内部会做 File.exists()/mkdirs()，
     * 这些调用会经过已安装的 native hook 回调到 redirectPath()，
     * 在 TrieTree 尚未构建完成时查询它是未定义行为。
     */
    private static volatile boolean sRedirectEnabled = false;

    /**
     * 重入保护。
     * redirectPath(File) 内部若调用 File.getAbsolutePath()，会再次进入被 hook 的
     * UnixFileSystem 方法，形成 无限递归 -> 栈溢出 -> native 层直接崩溃（Java 层
     * 连异常都收不到）。这里用 ThreadLocal 直接短路第二次进入。
     */
    private static final ThreadLocal<Boolean> sInRedirect = new ThreadLocal<>();

    /** 宿主自己的目录，绝对不参与重定向，否则宿主写日志/缓存会写进 guest 沙盒 */
    private static volatile String sHostFilesDir;
    private static volatile String sHostExtDir;
    private static volatile String sHostDataDir;

    public static boolean isRedirectEnabled() {
        return sRedirectEnabled;
    }

    public static void setHostDirs(String filesDir, String extDir, String dataDir) {
        sHostFilesDir = filesDir;
        sHostExtDir = extDir;
        sHostDataDir = dataDir;
    }

    private static boolean isHostOwnPath(String path) {
        String[] dirs = {sHostFilesDir, sHostExtDir, sHostDataDir};
        for (String d : dirs) {
            if (d != null && !d.isEmpty() && path.startsWith(d)) return true;
        }
        return false;
    }

    public static VCore get() {
        return sVCore;
    }

    // /data/data/com.google/  ----->  /data/data/com.virtual/data/com.google/
    public void addRedirect(String origPath, String redirectPath) {
        if (TextUtils.isEmpty(origPath) || TextUtils.isEmpty(redirectPath) || mRedirectMap.get(origPath) != null)
            return;
        //Add the key to TrieTree
        mTrieTree.add(origPath);
        mRedirectMap.put(origPath, redirectPath);
        File redirectFile = new File(redirectPath);
        if (!redirectFile.exists()) {
            FileUtils.mkdirs(redirectPath);
        }
        VNative.addIORule(origPath, redirectPath);
    }

    public void addBlackRedirect(String path) {
        if (TextUtils.isEmpty(path))
            return;
        sBlackTree.add(path);
    }

    public String redirectPath(String path) {
        if (TextUtils.isEmpty(path))
            return path;
        if (!sRedirectEnabled)
            return path;
        // 幂等：已经在沙盒的 SdCard 里就不要再改一次。
        // 注意要以 "/SdCard" 结尾也算命中 —— 沙盒根本身就是 .../SdCard，
        // 只判 contains("/SdCard/") 会漏掉它，导致根目录被改成 .../SdCard/SdCard。
        // 2.6.2 起按空间拆成 SdCard_u0/SdCard_u1…，同样要跳过。
        if (path.contains("/SdCard/") || path.endsWith("/SdCard")
                || path.contains("/SdCard_u")) {
            return path;
        }
        if (isHostOwnPath(path)) {
            return path;
        }
        // 防重入：被 hook 的 File API 在解析路径时可能再次回调到这里
        if (sInRedirect.get() != null)
            return path;
        sInRedirect.set(Boolean.TRUE);
        try {
            String search = sBlackTree.search(path);
            if (!TextUtils.isEmpty(search))
                return search;

            //Search the key from TrieTree
            String key = mTrieTree.search(path);
            if (!TextUtils.isEmpty(key)) {
                String target = mRedirectMap.get(key);
                if (target != null) {
                    path = path.replace(key, target);
                }
            }
            return path;
        } finally {
            sInRedirect.remove();
        }
    }

    public File redirectPath(File path) {
        if (path == null)
            return null;
        // 注意：这里不能用 getAbsolutePath()——它会走 UnixFileSystem（已被 hook），
        // 造成 hook -> Java -> getAbsolutePath -> hook 的无限递归。
        String pathStr = path.getPath();
        return new File(redirectPath(pathStr));
    }

    public String redirectPath(String path, Map<String, String> rule) {
        if (TextUtils.isEmpty(path))
            return path;
        if (!sRedirectEnabled)
            return path;
        if (isHostOwnPath(path))
            return path;
        if (sInRedirect.get() != null)
            return path;
        sInRedirect.set(Boolean.TRUE);
        try {
            //Search the key from TrieTree
            String key = mTrieTree.search(path);
            if (!TextUtils.isEmpty(key)) {
                String target = rule.get(key);
                if (target != null) {
                    path = path.replace(key, target);
                }
            }
            return path;
        } finally {
            sInRedirect.remove();
        }
    }

    public File redirectPath(File path, Map<String, String> rule) {
        if (path == null)
            return null;
        String pathStr = path.getPath();
        return new File(redirectPath(pathStr, rule));
    }

    // 由于正常情况Application已完成重定向，以下重定向是怕代码写死。
    public void enableRedirect(Context context) {
        // 构建期间关闭重定向查询，避免 File API 触发的 hook 回流
        sRedirectEnabled = false;
        Map<String, String> rule = new LinkedHashMap<>();
        Set<String> blackRule = new HashSet<>();
        String packageName = context.getPackageName();

        // 记录宿主自己的目录，避免宿主自身的文件访问被重定向
        try {
            android.content.Context host = HushCore.getContext();
            File hostExt = host.getExternalFilesDir(null);
            setHostDirs(
                    host.getFilesDir() == null ? null : host.getFilesDir().getAbsolutePath(),
                    hostExt == null ? null : hostExt.getAbsolutePath(),
                    host.getApplicationInfo().dataDir);
        } catch (Throwable ignored) {
        }

        try {
            ApplicationInfo packageInfo = HushCore.getBPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA, BActivityThread.getUserId());
            int systemUserId = HushCore.getHostUserId();
            rule.put(String.format("/data/data/%s/lib", packageName), packageInfo.nativeLibraryDir);
            rule.put(String.format("/data/user/%d/%s/lib", systemUserId, packageName), packageInfo.nativeLibraryDir);

            rule.put(String.format("/data/data/%s", packageName), packageInfo.dataDir);
            rule.put(String.format("/data/user/%d/%s", systemUserId, packageName), packageInfo.dataDir);
            File deDataDir = BEnvironment.getDeDataDir(packageName, BActivityThread.getUserId());
            FileUtils.mkdirs(deDataDir);
            FileUtils.mkdirs(new File(deDataDir, "databases"));
            FileUtils.mkdirs(new File(deDataDir, "files"));
            FileUtils.mkdirs(new File(deDataDir, "cache"));
            // code_cache 必须预建：加固壳（爱加密 i11111i111.zip、乐固 .legu）
            // 都是往自己的 code_cache 写解密后的 dex 再加载，父目录不存在时
            // open(O_CREAT) 直接 ENOENT，壳写失败又不检查，接着读同一个文件
            // 拿到 -1 当指针用 —— 现象是「页面出来了然后闪退」。
            FileUtils.mkdirs(new File(deDataDir, "code_cache"));
            FileUtils.mkdirs(new File(deDataDir, "app_webview"));
            // WebView 用的是 credential dataDir（ApplicationInfo.dataDir），
            // 不是 device-protected 目录；两边都建一份，避免 Chromium 在
            // 初始化时因为 app_webview 不存在而判「网络不可用」。
            FileUtils.mkdirs(new File(packageInfo.dataDir, "app_webview"));
            FileUtils.mkdirs(new File(deDataDir, "app_textures"));
            FileUtils.mkdirs(new File(deDataDir, "app_abc"));
            FileUtils.mkdirs(new File(deDataDir, "app_flutter"));
            FileUtils.mkdirs(new File(deDataDir, "app_geolocation"));
            FileUtils.mkdirs(new File(deDataDir, "app_hs"));
            FileUtils.mkdirs(new File(deDataDir, "shared_prefs"));
            FileUtils.mkdirs(new File(deDataDir, "no_backup"));

            if ("com.google.android.gms".equals(packageName)) {
                try {
                    File chimeraDirDe = new File(deDataDir, "app_chimera");
                    FileUtils.mkdirs(chimeraDirDe);
                    File cfgDe = new File(chimeraDirDe, "current_config.fb");
                    if (!cfgDe.exists()) {
                        cfgDe.createNewFile();
                        cfgDe.setReadable(true, false);
                        cfgDe.setWritable(true, false);
                    }

                    File chimeraDirCe = new File(packageInfo.dataDir, "app_chimera");
                    FileUtils.mkdirs(chimeraDirCe);
                    File cfgCe = new File(chimeraDirCe, "current_config.fb");
                    if (!cfgCe.exists()) {
                        cfgCe.createNewFile();
                        cfgCe.setReadable(true, false);
                        cfgCe.setWritable(true, false);
                    }
                } catch (Exception ignored) {}
            }

            rule.put(String.format("/data/user_de/%d/%s", systemUserId, packageName), deDataDir.getAbsolutePath());

            // Ensure ART profile directories exist and redirect both current and reference profiles
            File profilesRoot = new File(BEnvironment.getVBoxRoot(), "profiles");
            FileUtils.mkdirs(profilesRoot.getAbsolutePath());
            // broad redirect as a safety net
            rule.put("/data/misc/profiles", profilesRoot.getAbsolutePath());

            File profilesCurDir = new File(profilesRoot, String.format("cur/%d/%s", BActivityThread.getUserId(), packageName));
            File profilesRefDir = new File(profilesRoot, String.format("ref/%d/%s", BActivityThread.getUserId(), packageName));
            FileUtils.mkdirs(profilesCurDir.getAbsolutePath());
            FileUtils.mkdirs(profilesRefDir.getAbsolutePath());
            rule.put(String.format("/data/misc/profiles/cur/%d/%s", BActivityThread.getUserId(), packageName), profilesCurDir.getAbsolutePath());
            rule.put(String.format("/data/misc/profiles/ref/%d/%s", BActivityThread.getUserId(), packageName), profilesRefDir.getAbsolutePath());

            if (HushCore.getContext().getExternalCacheDir() != null && context.getExternalCacheDir() != null) {
                File external = BEnvironment.getExternalStorageDirectory();
               // File external = BEnvironment.getExternalUserDir(BActivityThread.getUserId());

                // sdcard
                rule.put("/sdcard", external.getAbsolutePath());
                rule.put(String.format("/storage/emulated/%d", systemUserId), external.getAbsolutePath());

                blackRule.add("/sdcard/Pictures");
                blackRule.add(String.format("/storage/emulated/%d/Pictures", systemUserId));
            }
            if (HushCore.get().setHideRoot()) {
                hideRoot(rule);
            }
            proc(rule);
        } catch (Throwable e) {
            e.printStackTrace();
            Diag.log("io", "build rules FAILED\n" + Diag.stack(e));
        }
        Diag.log("io", "rules built size=" + rule.size() + " black=" + blackRule.size());
        int idx = 0;
        for (String key : rule.keySet()) {
            try {
                get().addRedirect(key, rule.get(key));
                idx++;
            } catch (Throwable t) {
                Diag.log("io", "addRedirect FAILED at #" + idx + " " + key + "\n" + Diag.stack(t));
            }
        }
        Diag.log("io", "addRedirect done count=" + idx);
        for (String s : blackRule) {
            get().addBlackRedirect(s);
        }
        Diag.log("io", "blackRedirect done");
        // 退出追踪要赶在重定向生效前拿到 diag.log 的真实路径
        try {
            File df = Diag.file();
            if (df != null) {
                VNative.setDiagPath(df.getAbsolutePath());
                Diag.log("io", "diag path set -> " + df.getAbsolutePath());
            }
        } catch (Throwable t) {
            Diag.log("io", "exit trace arm FAILED " + t);
        }
        Diag.log("io", ">>> before VNative.enableIO");
        VNative.enableIO();
        Diag.log("io", "<<< after VNative.enableIO");
        // IO hook 装好了，之后所有文件访问才走重定向
        sRedirectEnabled = true;
        Diag.log("io", "redirect enabled");

        // 受控自检：确认重定向链路可以正常跑通（不依赖 guest 代码）
        try {
            boolean nio = false;
            try {
                nio = VNative.isNativeIoActive();
            } catch (Throwable ignored) {
            }
            Diag.log("io", "native libc hook active=" + nio);
            if (!nio) {
                // 普通 App 不装 libc hook（见 BoxCore::enableIO 的说明），
                // 下面这套探针只对白名单壳有意义，没装就别打，免得日志里
                // 出现 "probe native open errno 2" 这种必然失败的假异常。
                return;
            }

            String p = "/data/data/" + packageName;
            Diag.log("io", "probe redirect " + p + " -> " + redirectPath(p));
            Diag.log("io", "probe java exists(" + p + ")=" + new File(p).exists());
            try {
                int r = VNative.probeOpen(p);
                Diag.log("io", "probe native open(" + p + ")=" + (r == 0 ? "ok" : ("errno " + (-r))));
            } catch (Throwable t) {
                Diag.log("io", "probe native open FAILED " + t);
            }
            try {
                Diag.log("io", "native diag\n" + VNative.nativeIoDiag(p));
            } catch (Throwable t) {
                Diag.log("io", "native diag FAILED " + t);
            }
        } catch (Throwable t) {
            Diag.log("io", "probe FAILED\n" + Diag.stack(t));
        }
    }

    private void hideRoot(Map<String, String> rule) {
        // Standard su paths
        rule.put("/system/app/Superuser.apk", "/system/app/Superuser.apk-fake");
        rule.put("/sbin/su", "/sbin/su-fake");
        rule.put("/system/bin/su", "/system/bin/su-fake");
        rule.put("/system/xbin/su", "/system/xbin/su-fake");
        rule.put("/data/local/xbin/su", "/data/local/xbin/su-fake");
        rule.put("/data/local/bin/su", "/data/local/bin/su-fake");
        rule.put("/system/sd/xbin/su", "/system/sd/xbin/su-fake");
        rule.put("/system/bin/failsafe/su", "/system/bin/failsafe/su-fake");
        rule.put("/data/local/su", "/data/local/su-fake");
        rule.put("/su/bin/su", "/su/bin/su-fake");
        
        // Magisk paths — anti-cheat scans these
        rule.put("/sbin/.magisk", "/sbin/.magisk-fake");
        rule.put("/data/adb/magisk", "/data/adb/magisk-fake");
        rule.put("/cache/.disable_magisk", "/cache/.disable_magisk-fake");
        rule.put("/dev/.magisk.unblock", "/dev/.magisk.unblock-fake");
        
        // Xposed paths
        rule.put("/system/framework/XposedBridge.jar", "/system/framework/XposedBridge.jar-fake");
        rule.put("/system/xbin/xposed", "/system/xbin/xposed-fake");
        rule.put("/data/data/de.robv.android.xposed.installer", "/data/data/de.robv.android.xposed.installer-fake");
        rule.put("/data/data/org.lsposed.manager", "/data/data/org.lsposed.manager-fake");
        
        // Virtual environment indicators — anti-cheat checks for these
        rule.put("/data/data/com.bly.dkplat", "/data/data/com.bly.dkplat-fake");
        rule.put("/data/data/com.by.tp.vp", "/data/data/com.by.tp.vp-fake");
        rule.put("/data/data/com.lbe.parallel", "/data/data/com.lbe.parallel-fake");
        rule.put("/data/data/com.excelliance.dualaid", "/data/data/com.excelliance.dualaid-fake");
        rule.put("/data/data/com.ludashi.dualspace", "/data/data/com.ludashi.dualspace-fake");
        rule.put("/data/data/com.polestar.multiaccount", "/data/data/com.polestar.multiaccount-fake");
        rule.put("/data/data/com.nox.mopen.app", "/data/data/com.nox.mopen.app-fake");
    }

    private void proc(Map<String, String> rule) {
        // 默认关闭：静态快照会让原生引擎读到过期内容，是启动闪退的常见根因。
        if (!SdkBridge.sEnableFakeProc) return;
        int appPid = BActivityThread.getAppPid();
        int pid = Process.myPid();
        String selfProc = "/proc/self/";
        String proc = "/proc/" + pid + "/";

        File procDir = BEnvironment.getProcDir(appPid);

        // ====================================================================
        // cmdline — already handled, redirects to fake cmdline with real pkg name
        // ====================================================================
        String cmdline = new File(procDir, "cmdline").getAbsolutePath();
        rule.put(proc + "cmdline", cmdline);
        rule.put(selfProc + "cmdline", cmdline);

        // ====================================================================
        // /proc/self/maps 等「动态伪文件」绝不能做静态快照重定向。
        // maps 在进程生命周期内持续变化（每次 dlopen 都会新增一段），
        // 用启动瞬间的快照替换它会导致 Unity / IL2CPP 找不到自己的
        // libunity.so、libil2cpp.so，表现为"点开就闪退/黑屏"。
        // 这里只保留 cmdline（进程内恒定）与 status 的 TracerPid 处理。
        // ====================================================================
        File fakeStatusFile = new File(procDir, "status");
        createFakeProcStatus(fakeStatusFile);
        rule.put(proc + "status", fakeStatusFile.getAbsolutePath());
        rule.put(selfProc + "status", fakeStatusFile.getAbsolutePath());
    }

    /**
     * Create fake proc files that filter out virtual environment indicators.
     * We read the real file, strip entries containing our host package or 
     * virtual environment paths, then write the sanitized version.
     */
    private void createFakeProcFile(File fakeFile, String type) {
        try {
            FileUtils.mkdirs(fakeFile.getParent());
            int pid = Process.myPid();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream("/proc/" + pid + "/" + type)));
            StringBuilder sb = new StringBuilder();
            String line;
            String hostPkg = HushCore.getHostPkg();
            while ((line = reader.readLine()) != null) {
                // Skip lines that reveal the virtual environment
                if (hostPkg != null && line.contains(hostPkg)) continue;
                if (line.contains("HushCore")) continue;
                if (line.contains("hush")) continue;
                if (line.contains("vbox")) continue;
                if (line.contains("BlackBox")) continue;
                if (line.contains("black_box")) continue;
                if (line.contains("virtual_core")) continue;
                if (line.contains("libbgmi.so")) continue;
                sb.append(line).append("\n");
            }
            reader.close();
            
            java.io.FileWriter writer = new java.io.FileWriter(fakeFile);
            writer.write(sb.toString());
            writer.close();
        } catch (Exception e) {
            // If we can't create the fake file, just ignore — 
            // the redirect will fail and the real file is read
            Log.w(TAG, "Failed to create fake proc file: " + type, e);
        }
    }

    /**
     * Create a fake /proc/self/status that hides TracerPid and fixes Name/Pid
     */
    private void createFakeProcStatus(File fakeFile) {
        try {
            FileUtils.mkdirs(fakeFile.getParent());
            int pid = Process.myPid();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream("/proc/" + pid + "/status")));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                // Force TracerPid to 0 — non-zero means debugger attached
                // Anti-cheat checks this to detect analysis tools
                if (line.startsWith("TracerPid:")) {
                    sb.append("TracerPid:\t0\n");
                    continue;
                }
                sb.append(line).append("\n");
            }
            reader.close();
            
            java.io.FileWriter writer = new java.io.FileWriter(fakeFile);
            writer.write(sb.toString());
            writer.close();
        } catch (Exception e) {
            Log.w(TAG, "Failed to create fake proc status", e);
        }
    }
}

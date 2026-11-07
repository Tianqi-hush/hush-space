package com.hush.core;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.hush.HushCore;
import com.hush.entity.pm.InstallResult;
import com.hush.utils.Diag;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 内置 HMS Core（华为移动服务）。
 *
 * <p>和 GMS 同一套思路，区别是 GMS 直接复用系统里已装的包，而 HMS Core
 * 在非华为设备上根本不存在，所以把官方 APK 打进 assets，首次启用时释放
 * 出来再装进虚拟空间。
 *
 * <p>内置的是华为官方签名包（证书 SHA-256
 * e49d5c2c…），HMS 自己会校验自身与账号服务的签名，换成自制签名会直接
 * 判定环境异常并中止初始化，所以这里必须原样使用官方文件，不能裁剪
 * 架构目录、不能重签。
 */
public class HmsCore {

    /** HMS Core 本体。账号、推送、支付、定位等能力都在这个包里。 */
    public static final String HMS_PKG = "com.huawei.hwid";

    /**
     * 账号 kit：真正的华为账号登录界面（HUAWEI ID）。
     *
     * <p>HMS Core 6.16 把账号中心拆成了独立 APK，包名
     * {@code com.huawei.hms.account}，以 {@code com.huawei.hms.kit.type:hwid=app}
     * 的元数据登记为「hwid kit」。本体启动后先用
     * {@code FeatureManager.isFeatureInstalledInFramework("HwID_Features")}
     * 扫已安装的 kit：
     * <ul>
     *   <li>扫到了 → 直接拉起 kit 里的账号界面；</li>
     *   <li>扫不到 → 从 {@code assets/kits/} 解压再走 PackageInstaller 安装。</li>
     * </ul>
     *
     * <p>容器里没有 INSTALL_PACKAGES 权限，第二步必然失败（用户日志：
     * {@code FeaturesLoadingActivity installFail errorcode:2}），表现就是
     * 登录页一直转圈。所以必须把 kit 一起内置、一起装进空间。
     */
    public static final String ACCOUNT_KIT_PKG = "com.huawei.hms.account";

    /**
     * HMS SDK 的历史包名。真机上并不存在这个 APK，但老 SDK 会拿它做
     * 可用性探测（hwid → hms → hwid.tv 依次查）。容器里把它别名到
     * {@link #HMS_PKG}，否则光遇会一直认为 HMS Core 没装。
     */
    public static final String ALIAS_PKG = "com.huawei.hms";

    /**
     * 游戏服务 kit：光遇、应用市场这类「游戏/渠道」调用必须靠它。
     *
     * <p>HMS Core 里账号登录的 action 有两条：
     * <ul>
     *   <li>{@code com.huawei.hms.account.signIn} —— 普通应用登录，
     *       由 {@link #ACCOUNT_KIT_PKG} 里的 JosSignInActivity 处理；</li>
     *   <li>{@code com.huawei.hms.hwidjos.signIn} —— 游戏登录，HMS 的
     *       RuleEngine 会把前者改写成后者，而这条 action 只有
     *       GameServiceKit 里才声明（kit 名 {@code hwidjos}）。</li>
     * </ul>
     *
     * <p>2.6.17 之前只内置了账号 kit，游戏类应用一登录就报：
     * <pre>
     * ForegroundBus: Failed to get resolveInfo
     * ForegroundBus: errorReturn code:907135001,
     *                msg:action configure is invalid:com.huawei.hms.hwidjos.signIn
     * </pre>
     */
    public static final String GAME_KIT_PKG = "com.huawei.hms.game";

    /** 账号 kit 在 assets 里的位置。 */
    private static final String ACCOUNT_KIT_ASSET = "hms/kits/AccountKit-base.apk";
    private static final String ACCOUNT_KIT_NAME = "AccountKit-base.apk";

    /** 游戏服务 kit 在 assets 里的位置。 */
    private static final String GAME_KIT_ASSET = "hms/kits/GameServiceKit.apk";
    private static final String GAME_KIT_NAME = "GameServiceKit.apk";

    /**
     * HMS 的七个基础框架 kit。缺了它们，账号界面能画出第一屏，但往下点
     * 一步就闪退回桌面。
     *
     * <p>来由：hmscore.apk 的 {@code assets/raw/kits_config.json} 里有一份
     * {@code priority_install} 列表 —— Framework-common / manager / runtime /
     * ui / privacy / kitstore / webapp，它们在真机上是 HMS Core 安装完
     * 自己从 assets 解压安装的。容器里 kit 是我们手工装的，只装了
     * AccountKit，这七个从来没装过，于是：
     * <ul>
     *   <li>登录链路的跳板页 {@code HMSKitStandardC2NEStubActivity1} 的
     *       真实类在 {@code Framework-runtime.apk} 里。类加载不到，Activity
     *       退化成空壳，onCreate 里立刻 finish —— 表现就是「进登录页 1 秒
     *       后回到桌面」，而且一条异常都没有；</li>
     *   <li>kit 管理器 {@code com.huawei.hms.kitstore} 不存在，runtimekit
     *       查不到已装 kit 的路由表，跳板页同样无路可走；</li>
     *   <li>{@code Framework-ui} / {@code Framework-common} 是账号页的
     *       公共 UI 与工具类，缺了会在绘制阶段抛异常被 HMS 自己吞掉。</li>
     * </ul>
     *
     * <p>每一项：{@code {assets 路径, 文件名, 包名, 已安装模块名}}。模块名
     * 取自 kits_config.json 的 {@code kit_name}，多个用逗号分隔，HMS 的
     * FeatureInfoManager 用 {@code containsAll} 判定 kit 是否可用，名字错
     * 一个就会认为没装。
     */
    private static final String[][] FRAMEWORK_KITS = {
            {"hms/kits/Framework-common.apk", "Framework-common.apk",
                    "com.huawei.hms.commonkit", "fwcom-grs,fwcom-util,measurement,apptouch"},
            {"hms/kits/Framework-manager.apk", "Framework-manager.apk",
                    "com.huawei.hms.fwkit", "kpms,kams"},
            {"hms/kits/Framework-runtime.apk", "Framework-runtime.apk",
                    "com.huawei.hms.runtimekit", "runtime"},
            {"hms/kits/Framework-ui.apk", "Framework-ui.apk",
                    "com.huawei.hms.fwui", "fwui"},
            {"hms/kits/Framework-privacy.apk", "Framework-privacy.apk",
                    "com.huawei.hms.privacy", "privacykit"},
            {"hms/kits/Framework-kitstore.apk", "Framework-kitstore.apk",
                    "com.huawei.hms.kitstore", "kitstore"},
            {"hms/kits/Framework-webapp.apk", "Framework-webapp.apk",
                    "com.huawei.hms.webapp", "webapp-runtime"},
    };

    /**
     * 全部 HMS 系包名，{@link #HMS_PKG} 排第一。
     *
     * <p>容器里这些包各自只有一个 dex，跨包引用必须靠 ClassLoader 串联，
     * 顺序就是类的查找顺序：本体必须排在最前，否则 kit 里同名的旧版本类
     * 会盖掉本体的实现。</p>
     */
    public static java.util.List<String> allHmsPackages() {
        java.util.List<String> list = new java.util.ArrayList<>(10);
        list.add(HMS_PKG);
        list.add(ACCOUNT_KIT_PKG);
        list.add(GAME_KIT_PKG);
        for (String[] k : FRAMEWORK_KITS) list.add(k[2]);
        return list;
    }

    /** 荣耀账号（仅荣耀设备自带，不内置）。 */
    public static final String HONOR_ID_PKG = "com.hihonor.id";

    /**
     * 是不是容器内置的华为基础服务（HMS Core 本体 / 账号 kit / 其它 kit）。
     *
     * <p>这些包不是「用户应用」：不要给它们套加固壳兼容、不要加载 Xposed
     * 模块、启动守卫也不能因为一两次失败就把它们当壳来跑 ART hook。
     */
    public static boolean isHmsPackage(String pkg) {
        return pkg != null && (HMS_PKG.equals(pkg) || pkg.startsWith("com.huawei.hms."));
    }

    /**
     * 与 assets/hms/hmscore.apk 对应的版本。仅用于在释放前给界面显示，
     * 真正的版本以 APK 解析结果为准。
     */
    public static final String BUNDLED_VERSION = "6.16.4.362";

    private static final String ASSET_DIR = "hms";
    private static final String ASSET_NAME = "hmscore.apk";
    private static final String CACHE_DIR = "hms";
    private static final String CACHE_NAME = "hmscore.apk";

    private static String sVersionName;
    private static int sVersionCode;

    /** assets 里是否带了 HMS Core。 */
    public static boolean isBundled() {
        try (InputStream in = openAsset()) {
            return in != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static InputStream openAsset() {
        try {
            Context ctx = HushCore.getContext();
            if (ctx == null) return null;
            return ctx.getAssets().open(ASSET_DIR + "/" + ASSET_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把内置 APK 释放到内部目录。
     *
     * <p>不能放外部存储：虚拟安装器要读这个文件做解析和复制，外部
     * {@code /storage/emulated} 是 noexec + FUSE，虽然读文件本身允许，
     * 但宿主目录随时可能被系统清理，安装到一半文件没了会留下半个包。
     */
    public static File extract() {
        return extractAsset(ASSET_DIR + "/" + ASSET_NAME, CACHE_NAME, 50L * 1024 * 1024);
    }

    /**
     * 把 assets 里的一个 APK 释放到内部目录。
     *
     * @param assetPath   assets 相对路径
     * @param cacheName   内部目录里的文件名
     * @param minBytes    openFd 拿不到长度时的「够大就算完整」阈值
     */
    private static File extractAsset(String assetPath, String cacheName, long minBytes) {
        Context ctx = HushCore.getContext();
        if (ctx == null) return null;
        File dir = new File(ctx.getFilesDir(), CACHE_DIR);
        if (!dir.exists() && !dir.mkdirs()) return null;
        File out = new File(dir, cacheName);

        long expect = assetLength(assetPath);
        // 大多数构建会把 assets 里的 .apk 按原样存储，openFd 能拿到精确长度；
        // 万一被压缩过（openFd 抛异常），退化成「文件够大就算完整」，
        // 避免每次安装都重新释放几十 MB。
        boolean complete = out.isFile() && (expect > 0
                ? out.length() == expect
                : out.length() > minBytes);
        if (complete) {
            return out;
        }

        InputStream in = null;
        FileOutputStream fos = null;
        File tmp = new File(dir, cacheName + ".tmp");
        try {
            in = ctx.getAssets().open(assetPath);
            if (in == null) return null;
            fos = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                total += n;
            }
            fos.flush();
            fos.getFD().sync();
            fos.close();
            fos = null;
            if (out.exists() && !out.delete()) {
                Diag.log("hms", "旧副本删除失败 " + out);
            }
            if (!tmp.renameTo(out)) {
                Diag.log("hms", "释放失败 rename " + tmp);
                return null;
            }
            Diag.log("hms", "已释放 " + assetPath + " " + total + " bytes -> " + out);
            return out;
        } catch (Throwable t) {
            Diag.log("hms", "释放 " + assetPath + " 失败 " + t);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            try { if (fos != null) fos.close(); } catch (Throwable ignored) { }
            if (tmp.exists() && !out.exists()) tmp.delete();
        }
    }

    private static long assetLength(String assetPath) {
        try {
            Context ctx = HushCore.getContext();
            if (ctx == null) return -1;
            android.content.res.AssetFileDescriptor fd =
                    ctx.getAssets().openFd(assetPath);
            long len = fd.getLength();
            fd.close();
            return len;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 内置包的版本名。
     *
     * <p>注意不能在这里调 {@link #extract()}：设置页在组合阶段就会读版本，
     * 释放 54MB 文件会把主线程卡住好几秒。已释放过就读真实 APK，没释放过
     * 直接用编译期常量。
     */
    public static String bundledVersion() {
        if (sVersionName != null) return sVersionName;
        try {
            Context ctx = HushCore.getContext();
            if (ctx != null) {
                File cached = new File(new File(ctx.getFilesDir(), CACHE_DIR), CACHE_NAME);
                if (cached.isFile()) {
                    PackageInfo pi = HushCore.getPackageManager()
                            .getPackageArchiveInfo(cached.getAbsolutePath(), 0);
                    if (pi != null) {
                        sVersionName = pi.versionName == null ? BUNDLED_VERSION : pi.versionName;
                        sVersionCode = pi.versionCode;
                        return sVersionName;
                    }
                }
            }
        } catch (Throwable t) {
            Diag.log("hms", "读取内置版本失败 " + t);
        }
        return BUNDLED_VERSION;
    }

    public static boolean isInstalled(int userId) {
        try {
            return HushCore.get().isInstalled(HMS_PKG, userId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 已经装进空间的 HMS Core 版本。 */
    public static String installedVersion(int userId) {
        try {
            PackageInfo pi = HushCore.getBPackageManager()
                    .getPackageInfo(HMS_PKG, 0, userId);
            return pi == null || pi.versionName == null ? "" : pi.versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 把内置的 HMS Core 安装进指定空间。
     *
     * <p>必须直接按文件安装（{@code installByStorage}），不能走
     * {@code installPackageAsUser(packageName)}：那条路会优先复用系统里
     * 已装的同名包，华为设备上会拿到系统版本，非华为设备上直接失败，
     * 结果就是「有的机器能用、有的机器点了没反应」。
     */
    public static synchronized InstallResult install(int userId) {
        // 2.6.57：设置页的安装和 SpaceApplication 的「启动补装 kit」是两条
        // 独立线程，以前可以同时跑：两边都在解析/复制 APK，内存峰值翻倍，
        // 主进程被系统直接干掉（用户看到的就是「装 HMS 一定闪退回桌面」）。
        stage("start uid=" + userId);
        startHeartbeat();
        InstallResult r = null;
        try {
            r = installMain(userId);
            if (r != null && r.success) {
                // 本体装完必须立刻补 kit。HMS Core 第一次启动就会扫
                // FeatureManager，扫不到就去装、装不上就卡在加载页；
                // 等它先跑起来再补，用户会先看到一次「一直转圈」。
                installKits(userId);
                installVendorExtras(userId);
            }
            return r;
        } finally {
            stopHeartbeat();
            if (r != null && r.success) {
                stage("done");
                File f = stageFile();
                if (f != null) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }

    /** 2.6.57：安装阶段标记文件，进程被硬杀后用它复原死在哪一步。 */
    private static final String STAGE_FILE = ".hms_install_stage";

    private static File stageFile() {
        try {
            return new File(com.hush.core.env.BEnvironment.getSystemDir(), STAGE_FILE);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把当前安装阶段落盘并 fsync。
     *
     * <p>被 LMK / ROM 后台清理 / native 崩溃杀掉时，Java 层一行异常都不会有，
     * 日志只会停在最后一行。这个标记在下次启动时能直接说出「死在哪一步」。
     */
    static void stage(String s) {
        Diag.log("hms", "阶段 " + s + " " + mem());
        File f = stageFile();
        if (f == null) return;
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f, false);
            out.write((s + "\n").getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
        } catch (Throwable ignored) {
        } finally {
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String mem() {
        try {
            Runtime rt = Runtime.getRuntime();
            long java = (rt.totalMemory() - rt.freeMemory()) >> 20;
            long nat = android.os.Debug.getNativeHeapAllocatedSize() >> 20;
            return "java=" + java + "MB native=" + nat + "MB rss=" + rssKb() + "KB";
        } catch (Throwable t) {
            return "mem=?";
        }
    }

    /** /proc/self/status 的 VmRSS。走 raw syscall，避开容器 IO 重定向。 */
    private static long rssKb() {
        java.io.FileDescriptor fd = null;
        try {
            fd = android.system.Os.open("/proc/self/status",
                    android.system.OsConstants.O_RDONLY, 0);
            byte[] buf = new byte[4096];
            int n = android.system.Os.read(fd, buf, 0, buf.length);
            if (n <= 0) return -1;
            String s = new String(buf, 0, n, "UTF-8");
            for (String line : s.split("\n")) {
                if (line.startsWith("VmRSS:")) {
                    String[] p = line.trim().split("\\s+");
                    return Long.parseLong(p[1]);
                }
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        } finally {
            try {
                if (fd != null) android.system.Os.close(fd);
            } catch (Throwable ignored) {
            }
        }
    }

    private static volatile boolean sBeating;

    /** 安装期间每秒打一次心跳：如果进程猝死，最后一条心跳能看出内存曲线。 */
    private static void startHeartbeat() {
        if (sBeating) return;
        sBeating = true;
        Thread t = new Thread(() -> {
            int i = 0;
            while (sBeating) {
                try {
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    break;
                }
                if (!sBeating) break;
                Diag.log("hms", "心跳 " + (++i)
                        + " pid=" + android.os.Process.myPid() + " " + mem());
            }
        }, "hms-beat");
        t.setDaemon(true);
        t.start();
    }

    private static void stopHeartbeat() {
        sBeating = false;
    }

    /**
     * 2.6.57：启动时检查上次安装有没有被系统硬杀掉。
     *
     * <p>标记还在说明进程死在安装途中。此时把最新日志自动复制到
     * Download/HushSpace/export/，用户不用再翻设置页就能把「死亡现场」发出来。
     */
    public static void reportLastInstall() {
        File f = stageFile();
        if (f == null || !f.isFile()) return;
        String stage = "?";
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[512];
            int n = in.read(b);
            if (n > 0) stage = new String(b, 0, n, "UTF-8").trim();
        } catch (Throwable ignored) {
        }
        Diag.log("hms", "上次安装未完成，停在阶段【" + stage + "】——进程是在安装途中被系统杀掉的");
        try {
            File src = Diag.file();
            if (src != null && src.isFile()) {
                File dir = new File(android.os.Environment.getExternalStorageDirectory(),
                        "Download/HushSpace/export");
                if (dir.isDirectory() || dir.mkdirs()) {
                    String stamp = new java.text.SimpleDateFormat("MMdd-HHmmss",
                            java.util.Locale.US).format(new java.util.Date());
                    File out = new File(dir, "auto-" + BUNDLED_VERSION + "-" + stamp + ".log");
                    java.io.FileInputStream in = new java.io.FileInputStream(src);
                    java.io.FileOutputStream os = new java.io.FileOutputStream(out, false);
                    try {
                        os.write(("# 上次安装 HMS 中断于阶段 " + stage + "\n").getBytes("UTF-8"));
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                        os.flush();
                        os.getFD().sync();
                    } finally {
                        try { in.close(); } catch (Throwable ignored) { }
                        try { os.close(); } catch (Throwable ignored) { }
                    }
                    Diag.log("hms", "已自动导出死亡现场日志 " + out.getAbsolutePath());
                }
            }
        } catch (Throwable t) {
            Diag.log("hms", "自动导出失败 " + t);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** 只装 HMS Core 本体。 */
    private static InstallResult installMain(int userId) {
        stage("main-extract");
        File apk = extract();
        if (apk == null || !apk.isFile()) {
            return new InstallResult().installError("内置安装包缺失，请重新安装本应用");
        }
        try {
            stage("main-install");
            InstallResult r = HushCore.get().installPackageAsUserDirect(apk, userId);
            if (r != null && r.success) {
                stage("main-ok");
                Diag.log("hms", "安装成功 uid=" + userId + " ver=" + installedVersion(userId));
                // 装完就删，避免内部存储同时压着两份 54MB 的包。
                // 下次卸载重装时再从 assets 释放。
                apk.delete();
            } else {
                Diag.log("hms", "安装失败 uid=" + userId + " msg=" + (r == null ? "null" : r.msg));
            }
            return r == null ? new InstallResult().installError("安装失败") : r;
        } catch (Throwable t) {
            Diag.log("hms", "安装异常 " + t);
            return new InstallResult().installError(String.valueOf(t.getMessage()));
        }
    }

    /**
     * 老版本空间里只装了本体、没有账号 kit。升级后要在后台补一次，
     * 否则用户点开华为账号还是原地转圈。
     *
     * @return true 表示 kit 已就绪
     */
    public static synchronized boolean ensureKits(int userId) {
        try {
            if (!isInstalled(userId)) return false;
            boolean lackAccount = !isKitInstalled(ACCOUNT_KIT_PKG, userId);
            boolean lackGame = !isKitInstalled(GAME_KIT_PKG, userId);
            boolean lackFramework = !frameworkKitsInstalled(userId);
            boolean rescan = needKitRescan();
            if (lackAccount || lackGame || lackFramework || rescan) {
                // 缺包 → 装；本版本第一次启动 → 全部重装一遍，保证旧版本
                // 残留的 kit 也被换成 assets 里的这一份。
                boolean ok = installKits(userId, rescan);
                // HMS 的 ModuleLoader 在进程启动时把 kit 表整个读进内存，
                // 之后再装它不会刷新；旧进程还在的话，新装的 kit 等于没装，
                // 账号页依旧点开就退。所以补装完无条件重启一次 —— 此刻
                // 用户还没点图标，重启不会打断任何登录。
                if (ok) {
                    markKitsRescanned();
                    restartHms(userId, lackFramework ? "基础框架 kit 新装" : "升级后重扫 kit", true);
                }
                return ok;
            }
            // kit 都在，但「已安装模块」这份元数据是 2.6.19 才写对的
            // （2.6.18 给 game kit 写的是 HwID_Features，名字不对）。
            // HMS Core 在进程启动时把 kit 列表整个读进内存，之后不再刷新，
            // 所以升级到这一版后必须让它重启一次，否则内存里还是旧的那份
            // 「game kit 未安装」，hwidjos.signIn 依旧没注册。
            //
            // 只在「本版本第一次启动」时做：以前用进程内静态标记，
            // 用户每次冷启动都会把正在登录的 HMS 杀掉，
            // 表现就是账号页刚出来就没了。
            if (needKitRescan()) {
                restartHms(userId, "升级后重扫 kit", true);
            }
            return true;
        } catch (Throwable t) {
            Diag.log("hms", "补装 kit 失败 " + t);
            return false;
        }
    }

    /** 补装成功后落标记，下次启动不再重装。 */
    private static void markKitsRescanned() {
        try {
            android.content.Context c = HushCore.getContext();
            if (c == null) return;
            c.getSharedPreferences(SP_NAME, android.content.Context.MODE_PRIVATE)
                    .edit().putString(SP_KEY_RESCAN, KIT_REV).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 用于记录「哪个版本已经强制重扫过 kit」。 */
    private static final String SP_NAME = "hush_hms";
    private static final String SP_KEY_RESCAN = "kit_rescan_ver";
    /** 改这个值 = 让所有已装用户重新触发一次重扫。 */
    private static final String KIT_REV = "2.6.24-framework-kits";

    private static boolean needKitRescan() {
        try {
            android.content.Context c = HushCore.getContext();
            if (c == null) return false;
            android.content.SharedPreferences sp =
                    c.getSharedPreferences(SP_NAME, android.content.Context.MODE_PRIVATE);
            // 只读判断，不在这里落标记：标记一旦写下，本次重装失败就再也
            // 不会重试了（用户会一直停在「点开就退」）。真正写标记放在
            // markKitsRescanned()，由 ensureKits 在安装成功之后调。
            return !KIT_REV.equals(sp.getString(SP_KEY_RESCAN, ""));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 杀掉 HMS 相关进程，让下次拉起时重新扫描已装 kit。
     *
     * <p>要杀两个：{@code com.huawei.hwid}（CoreApplication / ModuleLoader）
     * 和 {@code com.huawei.hms.account}（账号 kit 的 MainEntry 进程，
     * ForegroundBus 的 action 表就在它里面）。</p>
     *
     * <p><b>HMS 进程还活着就绝不杀。</b>宿主是冷启动时在后台线程补装 kit 的，
     * 用户往往同一时刻已经点开了华为账号：这边 kit 刚解压完就把 hwid 进程
     * 干掉，用户看到的就是账号页闪了一下、1~2 秒后退回桌面，日志里一条异常
     * 都没有。kit 已经落盘，下次 HMS 冷启动 CoreApplication 必然重新扫到，
     * 为了一份内存里的 kit 列表打断正在进行的登录完全不划算。</p>
     */
    private static void restartHms(int userId, String why) {
        restartHms(userId, why, false);
    }

    /**
     * @param force true 表示「无论如何都杀」。只在本版本第一次补齐基础框架
     *              kit 时用：HMS 的内存里还没有 runtime / kitstore 的模块表，
     *              不重启就永远读不到，表现仍然是点开账号页立刻退回桌面。
     */
    private static void restartHms(int userId, String why, boolean force) {
        try {
            if (!force && isHmsRunning(userId)) {
                Diag.log("hms", "HMS 进程存活，跳过重启（" + why + "），kit 下次冷启动生效");
                return;
            }
            // 2.6.53：进程表里的 pid 可能是过期的，killPackageAsUser 会把 pid
            // 已回收给别的进程（包括宿主自己）的情况也一起「杀」，表现就是
            // 装完 HMS 后 hush 直接闪退回桌面。没活着的进程就根本不用杀。
            if (!hasAliveProcess(HMS_PKG, userId)
                    && !hasAliveProcess(ACCOUNT_KIT_PKG, userId)
                    && !anyFrameworkKitAlive(userId)) {
                Diag.log("hms", "没有 HMS 进程在跑，跳过重启（" + why + "）");
                return;
            }
            com.hush.core.system.BProcessManagerService.get()
                    .killPackageAsUser(HMS_PKG, userId);
            com.hush.core.system.BProcessManagerService.get()
                    .killPackageAsUser(ACCOUNT_KIT_PKG, userId);
            for (String[] k : FRAMEWORK_KITS) {
                try {
                    com.hush.core.system.BProcessManagerService.get()
                            .killPackageAsUser(k[2], userId);
                } catch (Throwable ignored) {
                }
            }
            Diag.log("hms", "重启 HMS 进程（" + why + "）");
        } catch (Throwable t) {
            Diag.log("hms", "重启 HMS 进程失败 " + t);
        }
    }

    /** 七个基础框架 kit 是否都装好了。 */
    private static boolean frameworkKitsInstalled(int userId) {
        for (String[] k : FRAMEWORK_KITS) {
            if (!isKitInstalled(k[2], userId)) return false;
        }
        return true;
    }

    /** HMS 或账号 kit 是否已有存活进程（有 pid 就算，不看前后台）。 */
    private static boolean isHmsRunning(int userId) {
        try {
            return hasAliveProcess(HMS_PKG, userId) || hasAliveProcess(ACCOUNT_KIT_PKG, userId);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasAliveProcess(String pkg, int userId) {
        try {
            java.util.List<com.hush.core.system.ProcessRecord> list =
                    com.hush.core.system.BProcessManagerService.get()
                            .getPackageProcessAsUser(pkg, userId);
            if (list == null) return false;
            for (com.hush.core.system.ProcessRecord r : list) {
                // 进程表里的 pid 可能是过期的，必须核对 /proc 里真的还在。
                if (r != null && r.pid > 0 && new java.io.File("/proc/" + r.pid).exists()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 七个框架 kit 里是否有任何一个进程还活着。 */
    private static boolean anyFrameworkKitAlive(int userId) {
        for (String[] k : FRAMEWORK_KITS) {
            try {
                if (k != null && k.length > 2 && hasAliveProcess(k[2], userId)) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean isKitInstalled(String pkg, int userId) {
        try {
            return HushCore.get().isInstalled(pkg, userId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 安装账号 kit + 游戏服务 kit。返回是否全部就绪。 */
    private static boolean installKits(int userId) {
        return installKits(userId, false);
    }

    /**
     * @param force true 时不管「已装」直接覆盖装一遍。
     *
     * <p>容器里的 kit 是手工装进去的，{@code isKitInstalled} 只看包在不在、
     * 不看版本。用户从旧版本一路升上来时，账号 kit 还是两三个版本前的旧包，
     * 旧包里没有新加的 provider（日志里的
     * {@code ClassNotFoundException: com.huawei.hwid.fingerprint.provider.HwIDFileProvider}），
     * provider 装不上，账号页就少一层初始化。所以每次 {@code KIT_REV} 变化
     * 都强制重装一遍，保证 assets 里是什么，容器里就是什么。</p>
     */
    private static boolean installKits(int userId, boolean force) {
        boolean a = installKit(userId, ACCOUNT_KIT_ASSET, ACCOUNT_KIT_NAME,
                ACCOUNT_KIT_PKG, 1024 * 1024, force);
        boolean g = installKit(userId, GAME_KIT_ASSET, GAME_KIT_NAME,
                GAME_KIT_PKG, 512 * 1024, force);
        // 七个基础框架 kit 必须一起装：账号页的第二屏、跳板页、kit 路由表
        // 全在它们里面。少装一个，症状都是「点开就退」，且没有异常。
        boolean f = true;
        for (String[] k : FRAMEWORK_KITS) {
            if (!installKit(userId, k[0], k[1], k[2], 64 * 1024, force)) f = false;
        }
        return a && g && f;
    }

    private static boolean installKit(int userId, String asset, String name,
                                      String pkg, long minBytes) {
        return installKit(userId, asset, name, pkg, minBytes, false);
    }

    private static boolean installKit(int userId, String asset, String name,
                                      String pkg, long minBytes, boolean force) {
        if (!force && isKitInstalled(pkg, userId)) return true;
        if (force && isKitInstalled(pkg, userId)) {
            // 覆盖安装前先卸掉：容器里 installPackageAsUserDirect 对同名
            // 已装包走的是「更新」，旧 dex 的 odex 缓存会留着，provider
            // 依然按旧的类表解析，等于没换。kit 本身没有用户数据，删了
            // 重装最干净。
            try {
                HushCore.get().uninstallPackageAsUser(pkg, userId);
            } catch (Throwable ignored) {
            }
        }
        File apk = extractAsset(asset, name, minBytes);
        if (apk == null || !apk.isFile()) {
            Diag.log("hms", "kit 资源缺失 " + asset);
            return false;
        }
        stage("kit " + pkg + " 释放完成");
        try {
            InstallResult r = HushCore.get().installPackageAsUserDirect(apk, userId);
            boolean ok = r != null && r.success;
            stage("kit " + pkg + " -> " + ok);
            Diag.log("hms", "kit 安装 " + pkg + " -> " + ok
                    + (ok ? "" : " msg=" + (r == null ? "null" : r.msg)));
            if (ok) {
                // 9.5MB 的包，装完就删。
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                if (GAME_KIT_PKG.equals(pkg)) {
                    // HMS Core 只在进程启动时扫一遍已装 kit（CoreApplication
                    // → ModuleLoader）。补装 hwidjos 时如果本体已经在跑，
                    // 它永远看不到这个新 kit，游戏登录还是 907135001。
                    restartHms(userId, "game kit 新装");
                }
            }
            return ok;
        } catch (Throwable t) {
            Diag.log("hms", "kit 安装异常 " + pkg + " " + t);
            return false;
        }
    }

    public static boolean uninstall(int userId) {
        try {
            // 先卸 kit 再卸本体：反过来本体没了，kit 的包设置里还挂着
            // 「依赖 hwid」的运行时状态，下次装回来容易读到脏的 kit 缓存。
            try {
                HushCore.get().uninstallPackageAsUser(GAME_KIT_PKG, userId);
            } catch (Throwable ignored) {
            }
            for (String[] k : FRAMEWORK_KITS) {
                try {
                    HushCore.get().uninstallPackageAsUser(k[2], userId);
                } catch (Throwable ignored) {
                }
            }
            try {
                HushCore.get().uninstallPackageAsUser(ACCOUNT_KIT_PKG, userId);
            } catch (Throwable ignored) {
            }
            HushCore.get().uninstallPackageAsUser(HMS_PKG, userId);
            return !isInstalled(userId);
        } catch (Throwable t) {
            Diag.log("hms", "卸载失败 " + t);
            return false;
        }
    }

    /**
     * 荣耀设备上还有几个独立预装包（账号、推送），没有公开 APK 可以内置，
     * 只能从系统里复制进空间。
     *
     * <p>2.6.7 起<b>只装荣耀账号</b>。推送相关的
     * {@code com.huawei.android.pushagent} / {@code com.hihonor.push} /
     * {@code com.hihonor.android.pushagent} 全部去掉：
     * <ul>
     *   <li>它们依赖系统签名与常驻权限，复制进容器也收不到真实推送；</li>
     *   <li>用户 2.6.6 日志里，装完 {@code com.hihonor.android.pushagent}
     *       约 1 秒后宿主进程直接重启（闪退），下一个要装的正是
     *       {@code com.huawei.android.pushagent}。这类系统包在部分荣耀
     *       机型上解析/登记时会把宿主带崩，代价远大于收益。</li>
     * </ul>
     *
     * <p>另外加了中断标记：如果这次附带安装中途把进程搞死了，下次启动
     * 看到标记就整段跳过，不会再陷进「装一次崩一次」的循环。
     */
    private static void installVendorExtras(int userId) {
        stage("vendor-skip");
        // com.hihonor.id 是设备上的系统级大包，按包名安装要把它的 base.apk
        // 整包读进主进程解析；honor 的包几百 MB，解析时 native 内存直接顶爆，
        // 进程被系统杀掉，日志里连一行异常都没有（2.6.48 / 2.6.53 两次日志
        // 都是死在 `hms: 附带安装 com.hihonor.id -> true` 之后 2 秒内）。
        // 它对 HMS 登录没有任何作用（注释里本来就写着「不影响 HMS 本体」），
        // 所以默认不自动装；需要荣耀账号的用户自己从应用列表装即可。
        Diag.log("hms", "跳过附带安装 com.hihonor.id（大包解析会拖死主进程）");
    }

    /** 旧实现，保留供显式调用。 */
    private static void installVendorExtrasLegacy(int userId) {
        final String[] extras = {
                "com.hihonor.id",
        };
        File guard = new File(com.hush.core.env.BEnvironment.getSystemDir(),
                ".hms_extras_running");
        if (guard.exists()) {
            Diag.log("hms", "上次附带安装中途中断，本次跳过（不影响 HMS 本体）");
            //noinspection ResultOfMethodCallIgnored
            guard.delete();
            return;
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            guard.createNewFile();
        } catch (Throwable ignored) {
        }
        try {
            for (String pkg : extras) {
                try {
                    if (HushCore.get().isInstalled(pkg, userId)) continue;
                    HushCore.getPackageManager().getPackageInfo(pkg, 0);
                } catch (Throwable notFound) {
                    continue;
                }
                try {
                    InstallResult r = HushCore.get().installPackageAsUser(pkg, userId);
                    Diag.log("hms", "附带安装 " + pkg + " -> " + (r != null && r.success));
                } catch (Throwable t) {
                    Diag.log("hms", "附带安装失败 " + pkg + " " + t);
                }
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            guard.delete();
        }
    }

    /** 是否属于华为 / 荣耀全家桶（用于应用列表角标与权限放行）。 */
    /**
     * 只有「账号 / 推送 / HMS 本体」这几个包才算华为系服务。
     *
     * <p>2.6.11 之前用的是 {@code startsWith("com.huawei.")}，范围太宽：
     * 华为应用市场（com.huawei.appmarket）、华为浏览器、云空间全被算成
     * HMS，应用列表里统统打上「HMS」角标；更糟的是包管理器会按「未安装」
     * 拦掉它们，应用商店在空间内查自己都查不到。
     *
     * <p>这些包必须是精确包名，不能用前缀 —— 应用市场不是账号服务。
     */
    private static final String[] HUAWEI_SERVICE_PKGS = {
            HMS_PKG,                        // com.huawei.hwid：HMS Core / 华为账号
            "com.huawei.hwid.core",         // 部分机型独立的账号核心包
            "com.huawei.hms",               // HMS SDK 探测用的虚拟包名（非独立 APK）
            "com.huawei.android.pushagent", // 华为推送
            "com.hihonor.id",               // 荣耀账号
            "com.hihonor.push",             // 荣耀推送
            "com.hihonor.android.pushagent",
    };

    public static boolean isHuaweiFamily(String packageName) {
        if (packageName == null) return false;
        for (String pkg : HUAWEI_SERVICE_PKGS) {
            if (pkg.equals(packageName)) return true;
        }
        return false;
    }

    /**
     * 容器内置的 HMS 组件包 —— 它们不是用户可以打开的应用。
     *
     * <p>2.6.24 把七个 Framework kit 装进空间之后，{@code AppRepo.loadCloned}
     * 把它们当成普通应用列了出来：privacy、webapp、common-kit、
     * framework-runtime、framework-ui、fwkit、HMS Core、hmsGame 一排花花绿绿
     * 的图标，用户根本分不清哪些能点。真机上这些是 HMS Core 的 split，
     * 桌面永远看不到。</p>
     *
     * <p>隐藏但不卸载：账号页、跳板页、kit 路由全都要靠它们。</p>
     */
    public static boolean isInternalKit(String packageName) {
        if (packageName == null) return false;
        return packageName.startsWith("com.huawei.hms.");
    }
}

package com.hush;

import android.app.Activity;
import android.widget.Toast;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import com.hush.activation.SdkBridge;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import dalvik.system.DexFile;
import java.io.IOException;
import com.hush.reflection.akshit.Reflection;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hush.env.android.app.BRActivityThread;
import com.hush.env.android.os.BRUserHandle;
import com.hush.app.LauncherActivity;
import com.hush.app.configuration.AppLifecycleCallback;
import com.hush.app.configuration.ClientConfiguration;
import com.hush.core.GmsCore;
import com.hush.core.HmsCore;
import com.hush.core.env.BEnvironment;
import com.hush.core.env.SpaceSettingsStore;
import com.hush.entity.SpaceSettings;
import com.hush.core.system.DaemonService;
import com.hush.core.system.ServiceManager;
import com.hush.core.system.user.BUserHandle;
import com.hush.core.system.user.BUserInfo;
import com.hush.entity.AppConfig;
import com.hush.entity.pm.InstallOption;
import com.hush.entity.pm.InstallResult;
import com.hush.entity.pm.InstalledModule;
import com.hush.fake.delegate.ContentProviderDelegate;
import com.hush.fake.frameworks.BActivityManager;
import com.hush.fake.frameworks.BJobManager;
import com.hush.fake.frameworks.BPackageManager;
import com.hush.fake.frameworks.BStorageManager;
import com.hush.fake.frameworks.BUserManager;
import com.hush.fake.frameworks.BXposedManager;
import com.hush.fake.hook.HookManager;
import com.hush.proxy.ProxyManifest;
import com.hush.utils.FileUtils;
import com.hush.utils.ShellUtils;
import com.hush.utils.Slog;
import com.hush.utils.compat.BuildCompat;
import com.hush.utils.compat.BundleCompat;
import com.hush.utils.compat.XposedParserCompat;
import com.hush.utils.provider.ProviderCall;
import com.hush.core.system.api.MetaActivationManager;
import org.lsposed.lsparanoid.Obfuscate;

/**
 * Created by @officialakshit01 on 3/30/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶 つ０
 * しーＪ
 * 此处无Bug
 */
@Obfuscate
@SuppressLint({ "StaticFieldLeak", "NewApi" })
public class HushCore extends ClientConfiguration {
    public static final String TAG = "HushCore";

    private static final HushCore sHushCore = new HushCore();
    private static Context sContext;
    private ProcessType mProcessType;
    private final Map<String, IBinder> mServices = new HashMap<>();
    private Thread.UncaughtExceptionHandler mExceptionHandler;
    private ClientConfiguration mClientConfiguration;
    private final List<AppLifecycleCallback> mAppLifecycleCallbacks = new ArrayList<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final int mHostUid = Process.myUid();
    private final int mHostUserId = BRUserHandle.get().myUserId();
    private AppConfig appConfig;

    public static HushCore get() {
        return sHushCore;
    }

    public Handler getHandler() {
        return mHandler;
    }

    public static PackageManager getPackageManager() {
        return sContext.getPackageManager();
    }

    public static String getHostPkg() {
        return get().getHostPackageName();
    }

    public static int getHostUid() {
        return get().mHostUid;
    }

    public static int getHostUserId() {
        return get().mHostUserId;
    }

    public static Context getContext() {
        return sContext;
    }

    public AppConfig getAppConfig() {
        return appConfig;
    }

    public Thread.UncaughtExceptionHandler getExceptionHandler() {
        return mExceptionHandler;
    }

    public void setExceptionHandler(Thread.UncaughtExceptionHandler exceptionHandler) {
        mExceptionHandler = exceptionHandler;
    }

    public static void setHideRoot(boolean hideRoot) {
        SdkBridge.sHideRoot = hideRoot;
    }

    public static void setHideXposed(boolean hide) {
        SdkBridge.sHideXposed = hide;
    }

    public static void setEnableDaemonService(boolean e) {
        SdkBridge.sEnableDaemonService = e;
    }

    /** 是否启用 /proc/self/{cmdline,status} 的静态快照重定向。 */
    public static void setFakeProc(boolean enable) {
        SdkBridge.sEnableFakeProc = enable;
    }

    public void doAttachBaseContext(Context context, ClientConfiguration clientConfiguration) {
        if (clientConfiguration == null) {
            throw new IllegalArgumentException("ClientConfiguration is null!");
        }

        Reflection.unseal(context);
        sContext = context;
        mClientConfiguration = clientConfiguration;
        initNotificationManager();

        String processName = getProcessName(getContext());
        if (processName.equals(HushCore.getHostPkg())) {
            mProcessType = ProcessType.Main;
            startLogcat();
        } else if (processName.endsWith(getContext().getString(R.string.vbox_service_name))) {
            mProcessType = ProcessType.Server;
        } else {
            mProcessType = ProcessType.BAppClient;
        }

        if (HushCore.get().isBlackProcess()) {
            BEnvironment.load();
        }

        if (isServerProcess()) {
            if (SdkBridge.sEnableDaemonService) {
                Intent intent = new Intent();
                intent.setClass(getContext(), DaemonService.class);
                if (BuildCompat.isOreo_MR1()) {
                    getContext().startForegroundService(intent);
                } else {
                    getContext().startService(intent);
                }
            }
        }
        HookManager.get().init();
    }

    private void enforceSdkActive() {
        // Neutralized - offline standalone
    }

    public void doCreate() {
        // fix contentProvider
        if (isBlackProcess()) {
            ContentProviderDelegate.init();
        }
        if (!isServerProcess()) {
            ServiceManager.initBlackManager();
        }
        // 2.6.53：主进程也拦一次「自杀」。装 HMS 时有人（旧进程表里过期的 pid、
        // 或 HMS 自己的 killPackageAsUser 转发）打到宿主 pid 上，日志里一条异常
        // 都没有、App 直接回到桌面。拦掉 pid == myPid 的 killProcess 就能看见。
        try {
            com.hush.core.hook.LsHooks.installSelfKillGuard();
        } catch (Throwable ignored) {
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    enforceSdkActive();
                } catch (Throwable ignored) {
                }
            }
        }, 6000);
    }

    public static Object mainThread() {
        return BRActivityThread.get().currentActivityThread();
    }

    public void startActivity(Intent intent, int userId) {
        if (intent == null) {
            return;
        }
        if (mClientConfiguration.isEnableLauncherActivity()) {
            LauncherActivity.launch(intent, userId);
        } else {
            getBActivityManager().startActivity(intent, userId);
        }
    }

    public void onBeforeMainLaunchApk(String packageName, int userid) {
        for (AppLifecycleCallback appLifecycleCallback : HushCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.beforeMainLaunchApk(packageName, userid);
        }
    }

    public static BJobManager getBJobManager() {
        return BJobManager.get();
    }

    public static BPackageManager getBPackageManager() {
        return BPackageManager.get();
    }

    public static BActivityManager getBActivityManager() {
        return BActivityManager.get();
    }

    public static BStorageManager getBStorageManager() {
        return BStorageManager.get();
    }

    public boolean launchApk(String packageName, int userId) {
        try {
            enforceSdkActive();
        } catch (com.hush.activation.SdkCore.GateException e) {
            return false; // SDK not active — silently block, Toast already shown by enforceSdkActive
        }
        onBeforeMainLaunchApk(packageName, userId);
        // 加固壳（网易 libunisec、腾讯乐固等）会校验自己是从手机的 /data/app 官方
        // 安装目录加载的。容器把 APK 拷进自己的目录再加载时，壳会静默拒绝解密
        // 自己的 dex，表现就是 Application.onCreate 抛 NoClassDefFoundError、秒退。
        // 手机上装着同一个包（签名一致、版本不更低）时，这里把这份应用切回系统
        // 安装路径 —— 只换代码路径，用户数据目录不动。
        try {
            preferSystemInstall(packageName, userId);
        } catch (Throwable t) {
            com.hush.utils.Diag.log("pm", "preferSystemInstall failed " + t);
        }
        // 宿主被系统回收后，上一轮 guest 进程可能还活着（孤儿），它攥着 guest 的
        // WebView 数据目录锁；不清掉的话新拉起的进程必然在 AwDataDirLock 崩掉。
        try {
            int reaped = com.hush.core.system.BProcessManagerService.get().reapOrphanProcesses();
            if (reaped > 0) {
                com.hush.utils.Diag.log("reap", "before launch " + packageName + " reaped=" + reaped);
            }
        } catch (Throwable t) {
            com.hush.utils.Diag.log("reap", "before launch FAILED " + t);
        }
        Intent launchIntentForPackage = getBPackageManager().getLaunchIntentForPackage(packageName, userId);
        if (launchIntentForPackage == null) {
            // 华为 / 荣耀的账号与基础服务和 GMS 一样完整跑在虚拟空间里，
            // 这里不再往真实系统上兜。HMS Core 本身没有 launcher 界面，
            // 装进空间后点图标没有反应是正常的 —— 它只提供后台服务。
            com.hush.utils.Diag.log("launch", "no launcher intent " + packageName
                    + " userId=" + userId);
            return false;
        }
        startActivity(launchIntentForPackage, userId);
        return true;
    }

    /**
     * 把「容器内拷贝的 APK」切回手机上的系统安装。
     *
     * <p>判断条件只有三条：同包名、签名一致、系统版本不比容器里的低。
     * 任何一条不满足都不动 —— 用户主动导入的更新版/改版继续用拷贝。
     */
    private void preferSystemInstall(String packageName, int userId) {
        PackageManager pm = getPackageManager();
        PackageInfo installed;
        try {
            installed = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        if (installed == null || installed.applicationInfo == null) {
            return;
        }
        String sysPath = installed.applicationInfo.sourceDir;
        if (sysPath == null || sysPath.isEmpty()) {
            return;
        }
        PackageInfo boxed;
        try {
            boxed = getBPackageManager().getPackageInfo(packageName, PackageManager.GET_SIGNATURES, userId);
        } catch (Throwable t) {
            return;
        }
        if (boxed == null || boxed.applicationInfo == null) {
            return;
        }
        String boxPath = boxed.applicationInfo.sourceDir;
        if (boxPath == null || boxPath.equals(sysPath)) {
            return;
        }
        if (installed.versionCode != boxed.versionCode) {
            // 版本不一致就不动：可能是用户主动换的版本，切过去会牵扯数据迁移
            return;
        }
        if (!sameSignature(installed, boxed)) {
            com.hush.utils.Diag.log("pm", "skip system switch, signature differs pkg=" + packageName);
            return;
        }
        InstallResult r = installPackageAsUser(packageName, userId);
        com.hush.utils.Diag.log("pm", "switch to system install pkg=" + packageName
                + " ok=" + (r != null && r.success) + "\n  from=" + boxPath + "\n  to=" + sysPath);
    }

    private static boolean sameSignature(PackageInfo a, PackageInfo b) {
        android.content.pm.Signature[] sa = a == null ? null : a.signatures;
        android.content.pm.Signature[] sb = b == null ? null : b.signatures;
        if (sa == null || sb == null || sa.length == 0 || sb.length == 0) {
            // 拿不到签名就当一致：同包名 + 版本不降级，切回官方路径只会更接近真机。
            return true;
        }
        return java.util.Arrays.equals(sa[0].toByteArray(), sb[0].toByteArray());
    }

    /**
     * 导入 APK 时，如果这个包本来就装在手机上（签名一致、系统版本不比导入的低），
     * 直接挂系统安装，不再往容器里拷一份。拷贝版会把加固壳逼疯（见
     * {@link #preferSystemInstall}），而且像光遇这种 split 安装还会丢掉
     * split_config 里的 dex/assets。
     *
     * @return 可以走系统安装的包名，null 表示按原样导入
     */
    private String preferInstalledPackage(File apk) {
        if (apk == null || !apk.isFile()) {
            return null;
        }
        PackageManager pm = getPackageManager();
        PackageInfo archive;
        try {
            archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        } catch (Throwable t) {
            return null;
        }
        if (archive == null || archive.packageName == null) {
            return null;
        }
        PackageInfo installed;
        try {
            installed = pm.getPackageInfo(archive.packageName, PackageManager.GET_SIGNATURES);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        if (installed == null || installed.applicationInfo == null
                || installed.applicationInfo.sourceDir == null) {
            return null;
        }
        if (installed.versionCode != archive.versionCode) {
            // 版本不同说明用户就是要装这个文件，按文件装
            return null;
        }
        if (!sameSignature(installed, archive)) {
            return null;
        }
        return archive.packageName;
    }

    public boolean isInstalled(String packageName, int userId) {
        try {
            enforceSdkActive();
        } catch (com.hush.activation.SdkCore.GateException e) {
            return false;
        }
        return getBPackageManager().isInstalled(packageName, userId);
    }

    public void uninstallPackageAsUser(String packageName, int userId) {
        enforceSdkActive();
        getBPackageManager().uninstallPackageAsUser(packageName, userId);
    }

    public void uninstallPackage(String packageName) {
        enforceSdkActive();
        getBPackageManager().uninstallPackage(packageName);
    }

    public InstallResult installPackageAsUser(String packageName, int userId) {
        try {
            enforceSdkActive();
        } catch (com.hush.activation.SdkCore.GateException e) {
            return new InstallResult().installError("sdk_not_active");
        }
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(packageName, 0);
            return getBPackageManager().installPackageAsUser(packageInfo.applicationInfo.sourceDir,
                    InstallOption.installBySystem(), userId);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return new InstallResult().installError(e.getMessage());
        }
    }

    public InstallResult installPackageAsUser(File apk, int userId) {
        try {
            enforceSdkActive();
        } catch (com.hush.activation.SdkCore.GateException e) {
            return new InstallResult().installError("sdk_not_active");
        }
        try {
            String installedPkg = preferInstalledPackage(apk);
            if (installedPkg != null) {
                InstallResult r = installPackageAsUser(installedPkg, userId);
                if (r != null && r.success) {
                    com.hush.utils.Diag.log("pm", "import -> system install " + installedPkg
                            + " (apk=" + apk.getAbsolutePath() + ")");
                    return r;
                }
                com.hush.utils.Diag.log("pm", "import -> system install failed, fallback to copy "
                        + installedPkg);
            }
        } catch (Throwable t) {
            com.hush.utils.Diag.log("pm", "import prefer failed " + t);
        }
        return getBPackageManager().installPackageAsUser(apk.getAbsolutePath(), InstallOption.installByStorage(),
                userId);
    }

    /**
     * 按文件直接安装，跳过「系统已装同名包则复用」的优化。
     *
     * <p>内置的 HMS Core 必须走这条路：华为设备上系统自带同名包，复用逻辑
     * 会装成系统版本；非华为设备上又找不到系统包，两边行为不一致。
     */
    public InstallResult installPackageAsUserDirect(File apk, int userId) {
        try {
            enforceSdkActive();
        } catch (com.hush.activation.SdkCore.GateException e) {
            return new InstallResult().installError("sdk_not_active");
        }
        if (apk == null || !apk.isFile()) {
            return new InstallResult().installError("安装包不存在");
        }
        return getBPackageManager().installPackageAsUser(apk.getAbsolutePath(),
                InstallOption.installByStorage(), userId);
    }

    public InstallResult installPackageAsUser(Uri apk, int userId) {
        try {
            enforceSdkActive();
        } catch (com.hush.activation.SdkCore.GateException e) {
            return new InstallResult().installError("sdk_not_active");
        }
        return getBPackageManager().installPackageAsUser(apk.toString(), InstallOption.installByStorage().makeUriFile(),
                userId);
    }

    public InstallResult installXPModule(File apk) {
        enforceSdkActive();
        return getBPackageManager().installPackageAsUser(apk.getAbsolutePath(),
                InstallOption.installByStorage().makeXposed(), BUserHandle.USER_XPOSED);
    }

    public InstallResult installXPModule(Uri apk) {
        enforceSdkActive();
        return getBPackageManager().installPackageAsUser(apk.toString(),
                InstallOption.installByStorage().makeXposed().makeUriFile(), BUserHandle.USER_XPOSED);
    }

    public InstallResult installXPModule(String packageName) {
        enforceSdkActive();
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(packageName, 0);
            String path = packageInfo.applicationInfo.sourceDir;
            return getBPackageManager().installPackageAsUser(path, InstallOption.installBySystem().makeXposed(),
                    BUserHandle.USER_XPOSED);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return new InstallResult().installError(e.getMessage());
        }
    }

    public void uninstallXPModule(String packageName) {
        enforceSdkActive();
        uninstallPackage(packageName);
    }

    public boolean isXPEnable() {
        enforceSdkActive();
        return BXposedManager.get().isXPEnable();
    }

    public void setXPEnable(boolean enable) {
        enforceSdkActive();
        BXposedManager.get().setXPEnable(enable);
    }

    public boolean isXposedModule(File file) {
        enforceSdkActive();
        return XposedParserCompat.isXPModule(file.getAbsolutePath());
    }

    public boolean isInstalledXposedModule(String packageName) {
        enforceSdkActive();
        return isInstalled(packageName, BUserHandle.USER_XPOSED);
    }

    public boolean isModuleEnable(String packageName) {
        enforceSdkActive();
        return BXposedManager.get().isModuleEnable(packageName);
    }

    public void setModuleEnable(String packageName, boolean enable) {
        enforceSdkActive();
        BXposedManager.get().setModuleEnable(packageName, enable);
    }

    /** 模块作用域：空列表 = 全部应用。 */
    public List<String> getModuleScope(String packageName) {
        enforceSdkActive();
        return BXposedManager.get().getModuleScope(packageName);
    }

    /** 保存模块作用域：传空列表恢复为全部应用。 */
    public void setModuleScope(String packageName, List<String> scope) {
        enforceSdkActive();
        BXposedManager.get().setModuleScope(packageName, scope);
    }

    public List<InstalledModule> getInstalledXPModules() {
        enforceSdkActive();
        return BXposedManager.get().getInstalledModules();
    }

    // ---------------- 空间配置（环境 / 区域 / 语言 / 时区） ----------------

    /** 全局配置：所有空间未单独覆盖时使用。 */
    public SpaceSettings getGlobalSpaceSettings() {
        return SpaceSettingsStore.getGlobal();
    }

    /** @return 真正落盘成功才返回 true。 */
    public boolean setGlobalSpaceSettings(SpaceSettings settings) {
        return SpaceSettingsStore.setGlobal(settings);
    }

    /** 单空间覆盖项；没配置过返回 null。 */
    public SpaceSettings getSpaceSettings(int userId) {
        return SpaceSettingsStore.getSpace(userId);
    }

    /** @return 真正落盘成功才返回 true。 */
    public boolean setSpaceSettings(int userId, SpaceSettings settings) {
        return SpaceSettingsStore.setSpace(userId, settings);
    }

    public void clearSpaceSettings(int userId) {
        SpaceSettingsStore.clearSpace(userId);
    }

    /** 合并后的最终配置（空间 > 全局 > 默认）。 */
    public SpaceSettings resolveSpaceSettings(int userId) {
        return SpaceSettingsStore.resolve(userId);
    }

    public List<ApplicationInfo> getInstalledApplications(int flags, int userId) {
        try {
            enforceSdkActive();
        } catch (com.hush.activation.SdkCore.GateException e) {
            return new java.util.ArrayList<>();
        }
        return getBPackageManager().getInstalledApplications(flags, userId);
    }

    public List<PackageInfo> getInstalledPackages(int flags, int userId) {
        try {
            enforceSdkActive();
        } catch (com.hush.activation.SdkCore.GateException e) {
            return new java.util.ArrayList<>();
        }
        return getBPackageManager().getInstalledPackages(flags, userId);
    }

    public void clearPackage(String packageName, int userId) {
        enforceSdkActive();
        BPackageManager.get().clearPackage(packageName, userId);
    }

    public void stopPackage(String packageName, int userId) {
        enforceSdkActive();
        BPackageManager.get().stopPackage(packageName, userId);
    }

    public boolean isAppRunning(String packageName, int userId) {
        enforceSdkActive();
        return getBPackageManager().isAppRunning(packageName, userId);
    }

    public List<BUserInfo> getUsers() {
        return BUserManager.get().getUsers();
    }

    public BUserInfo createUser(int userId) {
        return BUserManager.get().createUser(userId);
    }

    public ApplicationInfo getApplicationInfo(String packageName) {
        return BPackageManager.get().getApplicationInfo(packageName, 0, 0);
    }

    public void deleteUser(int userId) {
        BUserManager.get().deleteUser(userId);
    }

    public List<AppLifecycleCallback> getAppLifecycleCallbacks() {
        return mAppLifecycleCallbacks;
    }

    public void removeAppLifecycleCallback(AppLifecycleCallback appLifecycleCallback) {
        mAppLifecycleCallbacks.remove(appLifecycleCallback);
    }

    public void addAppLifecycleCallback(AppLifecycleCallback appLifecycleCallback) {
        mAppLifecycleCallbacks.add(appLifecycleCallback);
    }

    public boolean isSupportGms() {
        return GmsCore.isSupportGms();
    }

    public boolean isInstallGms(int userId) {
        return GmsCore.isInstalledGoogleService(userId);
    }

    public InstallResult installGms(int userId) {
        return GmsCore.installGApps(userId);
    }

    public boolean uninstallGms(int userId) {
        GmsCore.uninstallGApps(userId);
        return !GmsCore.isInstalledGoogleService(userId);
    }

    // ---------------- 内置 HMS Core ----------------

    /** assets 里是否带了 HMS Core 安装包。 */
    public boolean isHmsBundled() {
        return HmsCore.isBundled();
    }

    /** 这个空间里是否已经装了 HMS Core。 */
    public boolean isInstallHms(int userId) {
        return HmsCore.isInstalled(userId);
    }

    /** 空间里已装 HMS Core 的版本号。 */
    public String getInstalledHmsVersion(int userId) {
        return HmsCore.installedVersion(userId);
    }

    /** 内置包的版本号。 */
    public String getBundledHmsVersion() {
        return HmsCore.bundledVersion();
    }

    public InstallResult installHms(int userId) {
        return HmsCore.install(userId);
    }

    /**
     * 确保账号 kit（com.huawei.hms.account）也在空间里。
     *
     * <p>2.6.11 及以前只装了 HMS Core 本体，账号界面那个独立 APK 依赖
     * PackageInstaller 现场安装，容器里必然失败，登录页就永远转圈。
     * 升级后由宿主启动时后台补装。
     */
    public boolean ensureHmsKits(int userId) {
        return HmsCore.ensureKits(userId);
    }

    public boolean uninstallHms(int userId) {
        return HmsCore.uninstall(userId);
    }

    public IBinder getService(String name) {
        IBinder binder = mServices.get(name);
        if (binder != null && binder.isBinderAlive()) {
            return binder;
        }
        Bundle bundle = new Bundle();
        bundle.putString("_G_|_server_name_", name);
        Bundle vm = ProviderCall.callSafely(ProxyManifest.getBindProvider(), "VM", null, bundle);
        binder = BundleCompat.getBinder(vm, "_G_|_server_");
        Slog.d(TAG, "getService: " + name + ", " + binder);
        mServices.put(name, binder);
        return binder;
    }

    // Process type
    private enum ProcessType {
        Server, // Server process
        BAppClient, // Black app process
        Main, // Main process
    }

    public boolean isBlackProcess() {
        return mProcessType == ProcessType.BAppClient;
    }

    public boolean isMainProcess() {
        return mProcessType == ProcessType.Main;
    }

    public boolean isServerProcess() {
        return mProcessType == ProcessType.Server;
    }

    @Override
    public boolean setHideRoot() {
        return mClientConfiguration.setHideRoot();
    }

    @Override
    public String getHostPackageName() {
        return mClientConfiguration.getHostPackageName();
    }

    @Override
    public boolean requestInstallPackage(File file) {
        return mClientConfiguration.requestInstallPackage(file);
    }

    private void startLogcat() {
        new Thread(() -> {
            File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    getContext().getPackageName() + "_logcat.txt");
            FileUtils.deleteDir(file);
            ShellUtils.execCommand("logcat -c", false);
            ShellUtils.execCommand("logcat -f " + file.getAbsolutePath(), false);
        }).start();
    }

    private static String getProcessName(Context context) {
        int myPid = Process.myPid();
        String processName = null;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
            if (info.pid == myPid) {
                processName = info.processName;
                break;
            }
        }
        if (processName == null) {
            throw new RuntimeException("processName = null");
        }
        return processName;
    }

    public static boolean is64Bit() {
        if (BuildCompat.isM()) {
            return Process.is64Bit();
        } else {
            return Build.CPU_ABI.equals("arm64-v8a");
        }
    }

    private void initNotificationManager() {
        NotificationManager nm = (NotificationManager) HushCore.getContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        String CHANNEL_ONE_ID = HushCore.getContext().getPackageName() + ".vbox_core";
        String CHANNEL_ONE_NAME = "vbox_core";
        if (BuildCompat.isOreo_MR1()) {
            NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ONE_ID, CHANNEL_ONE_NAME,
                    NotificationManager.IMPORTANCE_HIGH);
            notificationChannel.enableLights(true);
            notificationChannel.setLightColor(Color.RED);
            notificationChannel.setShowBadge(true);
            notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(notificationChannel);
        }
    }

    // 20240801 add request permission add start 0
    public boolean checkSelfPermission(String permission) {
        return getPackageManager().checkPermission(permission, getHostPackageName()) == 0;
    }
    // 20240801 add request permission add end 0
}

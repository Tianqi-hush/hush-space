package com.hush.core.system.pm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;

import java.util.HashSet;
import java.util.Set;
import java.lang.reflect.Field;

import com.hush.env.android.content.pm.BRApplicationInfoL;
import com.hush.env.android.content.pm.BRApplicationInfoN;
import com.hush.env.android.content.pm.BRPackageParserSigningDetails;
import com.hush.env.android.content.pm.BRSigningInfo;
import com.hush.env.android.content.res.BRAssetManager;
import com.hush.HushCore;
import com.hush.core.env.AppSystemEnv;
import com.hush.core.env.BEnvironment;
import com.hush.entity.pm.InstallOption;
import com.hush.utils.ArrayUtils;
import com.hush.utils.FileUtils;
import com.hush.utils.compat.BuildCompat;

/**
 * Created by Milk on 4/15/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
@SuppressLint({"SdCardPath", "NewApi"})
public class PackageManagerCompat {

    public static PackageInfo generatePackageInfo(BPackageSettings ps, int flags, BPackageUserState state, int userId) {
        if (ps == null) {
            return null;
        }
        BPackage p = ps.pkg;
        if (p != null) {
            PackageInfo packageInfo = null;
            try {
                packageInfo = generatePackageInfo(p, flags, 0, 0, state, userId);
            } catch (Throwable t) {
                // 以前这里全吞掉，结果只看到上层拿到 null、日志里是 hms-absent，
                // 根本不知道为什么。留一条带异常的记录，下次可以直接定位是哪个
                // 组件（activity/service/provider）在解析时炸的。
                com.hush.utils.Diag.log("pm", "生成 PackageInfo 失败 " + p.packageName
                        + " flags=0x" + Integer.toHexString(flags) + " " + t);
            }
            return packageInfo;
        }
        return null;
    }

    public static PackageInfo generatePackageInfo(BPackage p, int flags, long firstInstallTime, long lastUpdateTime, BPackageUserState state, int userId) {
        if (!checkUseInstalledOrHidden(flags, state, p.applicationInfo)) {
            return null;
        }

        PackageInfo pi = new PackageInfo();
        pi.packageName = p.packageName;
        pi.versionCode = p.mVersionCode;
        pi.versionName = p.mVersionName;
        pi.sharedUserId = p.mSharedUserId;
        pi.sharedUserLabel = p.mSharedUserLabel;
        pi.applicationInfo = generateApplicationInfo(p, flags, state, userId);

        pi.firstInstallTime = firstInstallTime;
        pi.lastUpdateTime = lastUpdateTime;
        if (!p.requestedPermissions.isEmpty()) {
            String[] requestedPermissions = new String[p.requestedPermissions.size()];
            p.requestedPermissions.toArray(requestedPermissions);
            pi.requestedPermissions = requestedPermissions;
        }
        
        if ((flags & FileUtils.FileMode.MODE_IRUSR) != 0) {
            pi.gids = new int[0];
        }

        if ((flags & PackageManager.GET_CONFIGURATIONS) != 0) {
            int N = p.configPreferences != null ? p.configPreferences.size() : 0;
            if (N > 0) {
                pi.configPreferences = new ConfigurationInfo[N];
                p.configPreferences.toArray(pi.configPreferences);
            }
            N = p.reqFeatures != null ? p.reqFeatures.size() : 0;
            if (N > 0) {
                pi.reqFeatures = new FeatureInfo[N];
                p.reqFeatures.toArray(pi.reqFeatures);
            }
        }
        if ((flags & PackageManager.GET_ACTIVITIES) != 0) {
            pi.activities = null;
            final int N = p.activities.size();
            if (N > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[N];
                for (int i = 0; i < N; i++) {
                    final BPackage.Activity a = p.activities.get(i);
                    try {
                        ActivityInfo info = generateActivityInfo(a, flags, state, userId);
                        if (info != null) res[num++] = info;
                    } catch (Throwable t) {
                        logComponentFail(p.packageName, "activity", a.info.name, t);
                    }
                }
                pi.activities = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_RECEIVERS) != 0) {
            pi.receivers = null;
            final int N = p.receivers.size();
            if (N > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[N];
                for (int i = 0; i < N; i++) {
                    final BPackage.Activity a = p.receivers.get(i);
                    try {
                        ActivityInfo info = generateActivityInfo(a, flags, state, userId);
                        if (info != null) res[num++] = info;
                    } catch (Throwable t) {
                        logComponentFail(p.packageName, "receiver", a.info.name, t);
                    }
                }
                pi.receivers = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_SERVICES) != 0) {
            pi.services = null;
            final int N = p.services.size();
            if (N > 0) {
                int num = 0;
                final ServiceInfo[] res = new ServiceInfo[N];
                for (int i = 0; i < N; i++) {
                    final BPackage.Service s = p.services.get(i);
                    try {
                        ServiceInfo info = generateServiceInfo(s, flags, state, userId);
                        if (info != null) res[num++] = info;
                    } catch (Throwable t) {
                        logComponentFail(p.packageName, "service", s.info.name, t);
                    }
                }
                pi.services = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_PROVIDERS) != 0) {
            pi.providers = null;
            final int N = p.providers.size();
            if (N > 0) {
                int num = 0;
                final ProviderInfo[] res = new ProviderInfo[N];
                for (int i = 0; i < N; i++) {
                    final BPackage.Provider pr = p.providers.get(i);
                    try {
                        ProviderInfo providerInfo = generateProviderInfo(pr, flags, state, userId);
                        if (providerInfo != null) {
                            res[num++] = providerInfo;
                        }
                    } catch (Throwable t) {
                        logComponentFail(p.packageName, "provider", pr.info.name, t);
                    }
                }
                pi.providers = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_INSTRUMENTATION) != 0) {
            pi.instrumentation = null;
            int N = p.instrumentation.size();
            if (N > 0) {
                pi.instrumentation = new InstrumentationInfo[N];
                for (int i = 0; i < N; i++) {
                    pi.instrumentation[i] = generateInstrumentationInfo(
                            p.instrumentation.get(i), flags);
                }
            }
        }
        if ((flags & PackageManager.GET_PERMISSIONS) != 0) {
            pi.permissions = null;
            int N = p.permissions.size();
            if (N > 0) {
                pi.permissions = new PermissionInfo[N];
                for (int i = 0; i < N; i++) {
                    pi.permissions[i] = generatePermissionInfo(p.permissions.get(i), flags);
                }
            }
            pi.requestedPermissions = null;
            N = p.requestedPermissions.size();
            if (N > 0) {
                pi.requestedPermissions = new String[N];
                pi.requestedPermissionsFlags = new int[N];
                for (int i = 0; i < N; i++) {
                    final String perm = p.requestedPermissions.get(i);
                    pi.requestedPermissions[i] = perm;
                    // The notion of required permissions is deprecated but for compatibility.
//                    pi.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_REQUIRED;
//                    if (grantedPermissions != null && grantedPermissions.contains(perm)) {
//                        pi.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_GRANTED;
//                    }
                }
            }
        }
        PackageInfo base = null;
        try {
            base = HushCore.getContext().getPackageManager().getPackageInfo(p.packageName, flags);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if ((flags & PackageManager.GET_SIGNATURES) != 0) {
            if (base == null) {
                pi.signatures = p.mSignatures;
            } else {
                pi.signatures = base.signatures;
            }
        }
        if (BuildCompat.isPie()) {
            if ((flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0) {
                if (base == null) {
                    PackageParser.SigningDetails signingDetails = PackageParser.SigningDetails.UNKNOWN;
                    BRPackageParserSigningDetails.get(signingDetails)._set_signatures(p.mSigningDetails.signatures);
                    pi.signingInfo = BRSigningInfo.get()._new(signingDetails);
                } else {
                    pi.signingInfo = base.signingInfo;
                }
            }
        }
        return pi;
    }

    public static ActivityInfo generateActivityInfo(BPackage.Activity a, int flags, BPackageUserState state, int userId) {
        if (!checkUseInstalledOrHidden(flags, state, a.info.applicationInfo)) {
            return null;
        }
        // Make shallow copies so we can store the metadata safely
        ActivityInfo ai = new ActivityInfo(a.info);
        ai.metaData = a.metaData;
        ai.processName = BPackageManagerService.fixProcessName(ai.packageName, ai.processName);
        ai.applicationInfo = generateApplicationInfo(a.owner, flags, state, userId);
        return ai;
    }

    public static ServiceInfo generateServiceInfo(BPackage.Service s, int flags, BPackageUserState state, int userId) {
        if (!checkUseInstalledOrHidden(flags, state, s.info.applicationInfo)) {
            return null;
        }
        // Make shallow copies so we can store the metadata safely
        ServiceInfo si = new ServiceInfo(s.info);
        si.metaData = s.metaData;
        si.processName = BPackageManagerService.fixProcessName(si.packageName, si.processName);
        si.applicationInfo = generateApplicationInfo(s.owner, flags, state, userId);
        return si;
    }

    public static ProviderInfo generateProviderInfo(BPackage.Provider p, int flags, BPackageUserState state, int userId) {
        if (!checkUseInstalledOrHidden(flags, state, p.info.applicationInfo)) {
            return null;
        }
        // Make shallow copies so we can store the metadata safely
        ProviderInfo pi = new ProviderInfo(p.info);
        if (pi.authority == null) return null;
            pi.metaData = p.metaData;
            pi.processName = BPackageManagerService.fixProcessName(pi.packageName, pi.processName);
        if ((flags & FileUtils.FileMode.MODE_ISUID) == 0) {
            pi.uriPermissionPatterns = null;
        }
        pi.applicationInfo = generateApplicationInfo(p.owner, flags, state, userId);
        return pi;
    }

    public static PermissionInfo generatePermissionInfo(BPackage.Permission p, int flags) {
        if (p == null) return null;
        if ((flags & FileUtils.FileMode.MODE_IWUSR) == 0) {
            return p.info;
        }
        PermissionInfo pi = new PermissionInfo(p.info);
        pi.metaData = p.metaData;
        return pi;
    }

    public static InstrumentationInfo generateInstrumentationInfo(BPackage.Instrumentation i, int flags) {
        if (i == null) return null;
        if ((flags & PackageManager.GET_META_DATA) == 0) {
            return i.info;
        }
        InstrumentationInfo ii = new InstrumentationInfo(i.info);
        ii.metaData = i.metaData;
        return ii;
    }

    public static ApplicationInfo generateApplicationInfo(BPackage p, int flags, BPackageUserState state, int userId) {
        if (!checkUseInstalledOrHidden(flags, state, p.applicationInfo)) {
            return null;
        }
        ApplicationInfo baseApplication;
        try {
            baseApplication = HushCore.getPackageManager().getApplicationInfo(HushCore.getHostPkg(), flags);
        } catch (Exception e) {
            return null;
        }
        String sourceDir = p.baseCodePath;
        if (p.applicationInfo == null) {
            p.applicationInfo = HushCore.getPackageManager().getPackageArchiveInfo(sourceDir, 0).applicationInfo;
        }
        ApplicationInfo ai = new ApplicationInfo(p.applicationInfo);
        // HMS 的协议页 / H5 容器里混着 http 资源，真机上它的清单开了
        // usesCleartextTraffic。容器里 WebView 的明文策略取自当前进程这份
        // ApplicationInfo，宿主 targetSdk=28 默认是「禁止明文」——页面加载
        // 被 Chromium 拦掉后只显示「无网络」。这里对 HMS 系包强制打开。
        if (com.hush.core.HmsCore.HMS_PKG.equals(p.packageName)
                || isHmsKitPackage(p.packageName)) {
            // ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC = 1 << 23（@hide 常量，
            // 这里写字面量，避免公共 SDK 里找不到）。
            ai.flags |= 1 << 23;
        }
        // AccountKit-base.apk 的清单里写的是
        //   <application android:name="com.huawei.hwid.common.app.HwIDApplication">
        // 这个类在 hmscore.apk 里，不在 kit 自己的 dex 里。kit 被当成独立包
        // 安装后，进程一起来 LoadedApk 就抛
        //   ClassNotFoundException: com.huawei.hwid.common.app.HwIDApplication
        // → Unable to instantiate application → com.huawei.hms.account.core
        // 永远起不来，登录页只能报「无法获取数据」。
        // kit 只是功能模块，不需要自己的 Application，统一退化成 Framework 的。
        // 2.6.18 起不再只看 com.huawei.hwid 前缀：GameServiceKit 的
        // JosApplication 虽然在自己的 dex 里，但真机上它是 split、跑在
        // com.huawei.hwid 进程里、共用 CoreApplication；容器里当独立包装，
        // 起来就是另一个进程、没有 CoreApplication 铺垫，onCreate 必炸。
        //
        // 2.6.31 试过让 account kit 用 CoreApplication，想给 kit 页面补上全局
        // Context。结果更糟：CoreApplication 在 kit 进程里拉起 Tracker
        // （NoSuchMethodError: W.b.e）、multidex 重排导致 kit 页面类层次解析错，
        // 主线程直接 ClassCastException。kit 进程就该是轻量进程，不要 Application。
        if (isHmsKitPackage(p.packageName) && ai.className != null) {
            ai.className = "android.app.Application";
        }
        // HMS 的账号 / 推送 kit 靠清单里的 com.huawei.hms.kit.type:* 元数据
        // 向 HMS Core 登记自己。FeatureManager 扫描已装 kit 时如果拿不到
        // metaData，就会把「装了」判成「没装」，接着去走 PackageInstaller
        // 现场安装 —— 容器里没有 INSTALL_PACKAGES，直接失败，登录页卡死。
        // 所以这几个包不看 flags，metaData 一律带上。
        if ((flags & PackageManager.GET_META_DATA) != 0
                || isHmsKitPackage(p.packageName)) {
            ai.metaData = p.mAppMetaData;
        }
        // HMS Core 的账号界面不在本体里，而是以 split / kit 形式存在
        // （HwID_Features）。它启动时先问 FeatureInstallManager：
        //   getAllInstalledModules() → FeatureInfoManager
        //   → metaData["com.android.dynamic.apk.fused.modules"] + splitNames
        // 容器里既没有 split，也没有安装权限，这个集合永远是空的，
        // 于是它去「现场安装」→ 失败 → 登录页一直转圈。
        //
        // 2.6.18 起 kit 包也补：账号进程日志里一直是
        //   FeatureManager: featrues:[]
        //   featrue:HwID_Features is installedInFramework result:false
        // 说明它读的是「当前这个包」（kit 自己）的 fused.modules，
        // 只给本体的 ApplicationInfo 补是漏的。
        if (com.hush.core.HmsCore.HMS_PKG.equals(p.packageName)
                || isHmsKitPackage(p.packageName)) {
            // 本体的 metaData 只有在 flags 带 GET_META_DATA 时才赋值，
            // 没带的时候这里拿到 null，退回 p.mAppMetaData，不然会把
            // kit.type 之类的登记信息整个抹掉。
            Bundle base = ai.metaData != null ? ai.metaData : p.mAppMetaData;
            ai.metaData = withHmsFusedModule(p.packageName, base);
            logFusedOnce(p.packageName, ai.metaData);
        }
        ai.dataDir = BEnvironment.getDataDir(ai.packageName, userId).getAbsolutePath();
        if (!p.installOption.isFlag(InstallOption.FLAG_SYSTEM)) {
            ai.nativeLibraryDir = BEnvironment.getAppLibDir(ai.packageName).getAbsolutePath();
        }
        ai.processName = BPackageManagerService.fixProcessName(p.packageName, ai.packageName);
        ai.publicSourceDir = sourceDir;
        ai.sourceDir = sourceDir;
        ai.uid = p.mExtras.appId;
//        ai.uid = baseApplication.uid;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Field privateFlags = ApplicationInfo.class.getDeclaredField("privateFlags");
                privateFlags.setAccessible(true);
                int flagsValue = (int) privateFlags.get(ai);
                flagsValue &= ~32768; // PRIVATE_FLAG_ISOLATED_SPLIT_LOADING
                privateFlags.set(ai, flagsValue);
            } catch (Throwable e) {}
            try {
                Field splitClassLoaderNames = ApplicationInfo.class.getDeclaredField("splitClassLoaderNames");
                splitClassLoaderNames.setAccessible(true);
                splitClassLoaderNames.set(ai, null);
            } catch (Throwable e) {}
            try {
                Field splitDependencies = ApplicationInfo.class.getDeclaredField("splitDependencies");
                splitDependencies.setAccessible(true);
                splitDependencies.set(ai, null);
            } catch (Throwable e) {}
        }

        if (BuildCompat.isL()) {
            BRApplicationInfoL.get(ai)._set_primaryCpuAbi(Build.CPU_ABI);
            BRApplicationInfoL.get(ai)._set_scanPublicSourceDir(BRApplicationInfoL.get(baseApplication).scanPublicSourceDir());
            BRApplicationInfoL.get(ai)._set_scanSourceDir(BRApplicationInfoL.get(baseApplication).scanSourceDir());
        }
        if (BuildCompat.isN()) {
            ai.deviceProtectedDataDir = BEnvironment.getDeDataDir(p.packageName, userId).getAbsolutePath();

            if (BRApplicationInfoN.get(ai)._check_deviceEncryptedDataDir() != null) {
                BRApplicationInfoN.get(ai)._set_deviceEncryptedDataDir(ai.deviceProtectedDataDir);
            }
            if (BRApplicationInfoN.get(ai)._check_credentialEncryptedDataDir() != null) {
                BRApplicationInfoN.get(ai)._set_credentialEncryptedDataDir(ai.dataDir);
            }
            if (BRApplicationInfoN.get(ai)._check_deviceProtectedDataDir() != null) {
                BRApplicationInfoN.get(ai)._set_deviceProtectedDataDir(ai.deviceProtectedDataDir);
            }
            if (BRApplicationInfoN.get(ai)._check_credentialProtectedDataDir() != null) {
                BRApplicationInfoN.get(ai)._set_credentialProtectedDataDir(ai.dataDir);
            }
        }
        fixJar(ai);
        return ai;
    }

    private static boolean checkUseInstalledOrHidden(int flags, BPackageUserState state,
                                                     ApplicationInfo appInfo) {
        if (AppSystemEnv.isBlackPackage(appInfo.packageName))
            return false;
        // Returns false if the package is hidden system app until installed.
        return state.installed && !state.hidden;
    }

    private static void fixJar(ApplicationInfo info) {
        String APACHE_LEGACY_JAR = "/system/framework/org.apache.http.legacy.boot.jar";
        String APACHE_LEGACY_JAR_Q = "/system/framework/org.apache.http.legacy.jar";
        Set<String> sharedLibraryFileList = new HashSet<>();
        if (BuildCompat.isQ()) {
            if (!FileUtils.isExist(APACHE_LEGACY_JAR_Q)) {
                sharedLibraryFileList.add(APACHE_LEGACY_JAR);
            } else {
                sharedLibraryFileList.add(APACHE_LEGACY_JAR_Q);
            }
        } else {
            sharedLibraryFileList.add(APACHE_LEGACY_JAR);
        }
//        if (BXposedManagerService.get().isXPEnable()) {
//            ApplicationInfo base = HushCore.getContext().getApplicationInfo();
//            sharedLibraryFileList.add(base.sourceDir);
//        }
//        sharedLibraryFileList.add(BEnvironment.JUNIT_JAR.getAbsolutePath());
        info.sharedLibraryFiles = sharedLibraryFileList.toArray(new String[]{});
    }

    public static Resources getResources(Context context, ApplicationInfo appInfo) {
        BPackageSettings ps = BPackageManagerService.get().getBPackageSetting(appInfo.packageName);
        if (ps != null) {
            AssetManager assets = BRAssetManager.get()._new();
            BRAssetManager.get(assets).addAssetPath(ps.pkg.baseCodePath);
            Resources hostRes = context.getResources();
            return new Resources(assets, hostRes.getDisplayMetrics(), hostRes.getConfiguration());
        }
        return null;
    }

    private static final String HMS_FUSED_MODULES = "com.android.dynamic.apk.fused.modules";
    /** HMS Core 本体里的账号界面模块（真机上是 hmscore.apk 的一个 split）。 */
    private static final String HMS_WID_MODULE = "HwID_Features";

    /**
     * 每个包在「已安装模块」这一栏里必须出现的名字。
     *
     * <p>{@code FeatureInfoManager.b()} 的取数是：
     * <pre>
     *   metaData["com.android.dynamic.apk.fused.modules"].split(",")
     *   + PackageInfo.splitNames
     * </pre>
     * 然后 {@code getFeatureMasterSplitsNames()} 去掉 {@code config.*}，
     * 调用方再拿 {@code kit_name.split(";")} 去做 {@code containsAll}。
     *
     * <p>也就是说 <b>模块名必须逐字等于 kits_config.json 里的 kit_name</b>。
     * 2.6.18 给所有 kit 包一律写 {@code HwID_Features}，对
     * {@code com.huawei.hms.game} 完全无效 —— 它要的是
     * {@code core/game/payjos/hwidjos/iapjos/jos}，于是 FeatureManager
     * 一直认为 GameServiceKit 没装，{@code HwidJosMainEntry.onCreated}
     * 不会被调用，{@code hwidjos.signIn} 也就永远没注册，
     * 游戏登录固定报 907135001。
     *
     * <p>这张表是照着内置的 kits_config.json 抄的，新增 kit 时同步加一行。
     */
    private static String hmsFusedModulesOf(String pkg) {
        if (com.hush.core.HmsCore.HMS_PKG.equals(pkg)) {
            // 账号界面（HwID_Features）是 hmscore 本体提供的。
            return HMS_WID_MODULE;
        }
        if ("com.huawei.hms.account".equals(pkg)) {
            return "hwid,account";
        }
        if ("com.huawei.hms.game".equals(pkg)) {
            return "core,game,payjos,hwidjos,iapjos,jos";
        }
        // HMS 的七个基础框架 kit。名字必须和 kits_config.json 里的
        // kit_name 完全一致：FeatureInfoManager 用 containsAll 判定，
        // 少一个、拼错一个，HMS 就认为这个 kit 没装，跳板页照样找不到类。
        if ("com.huawei.hms.commonkit".equals(pkg)) {
            return "fwcom-grs,fwcom-util,measurement,apptouch";
        }
        if ("com.huawei.hms.fwkit".equals(pkg)) {
            return "kpms,kams";
        }
        if ("com.huawei.hms.runtimekit".equals(pkg)) {
            return "runtime";
        }
        if ("com.huawei.hms.fwui".equals(pkg)) {
            return "fwui";
        }
        if ("com.huawei.hms.privacy".equals(pkg)) {
            return "privacykit";
        }
        if ("com.huawei.hms.kitstore".equals(pkg)) {
            return "kitstore";
        }
        if ("com.huawei.hms.webapp".equals(pkg)) {
            return "webapp-runtime";
        }
        return null;
    }

    /** 每个包只打一行，确认 kit 的「已安装模块」真的写进去了。 */
    private static final java.util.Set<String> sLoggedFused =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void logFusedOnce(String pkg, Bundle meta) {
        try {
            if (pkg == null || !sLoggedFused.add(pkg)) return;
            String v = meta == null ? null : meta.getString(HMS_FUSED_MODULES);
            com.hush.utils.Diag.log("pm", "fused " + pkg + " -> " + v);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 往 HMS 相关包的 metaData 里补「已安装模块」标记。
     *
     * <p>容器里装不了 split，但 kit 的类本来就在各自 APK 里，缺的只是
     * 这份「已安装」标记。
     *
     * <p>必须复制：{@code p.mAppMetaData} 是全局共享对象，直接改会污染
     * 后续所有查询。
     */
    private static Bundle withHmsFusedModule(String pkg, Bundle meta) {
        String add = hmsFusedModulesOf(pkg);
        if (add == null) return meta;
        Bundle out = meta == null ? new Bundle() : new Bundle(meta);
        String old = out.getString(HMS_FUSED_MODULES);
        if (old == null || old.isEmpty()) {
            out.putString(HMS_FUSED_MODULES, add);
            return out;
        }
        StringBuilder sb = new StringBuilder(old);
        boolean changed = false;
        for (String m : add.split(",")) {
            if (m.isEmpty()) continue;
            // 用逗号切分后逐项比对，避免 "game" 命中 "hwidjosgame" 这种子串误判。
            boolean has = false;
            for (String o : old.split(",")) {
                if (o.trim().equals(m)) {
                    has = true;
                    break;
                }
            }
            if (!has) {
                sb.append(',').append(m);
                changed = true;
            }
        }
        if (changed) out.putString(HMS_FUSED_MODULES, sb.toString());
        return out;
    }

    /**
     * 是不是 HMS 拆出来的 kit 包（{@code com.huawei.hms.account} 等）。
     *
     * <p>它们用清单 meta-data 里的 {@code com.huawei.hms.kit.type:xxx}
     * 向 HMS Core 登记，HMS 启动时会扫这份元数据决定要不要现场安装。
     */
    private static boolean isHmsKitPackage(String pkg) {
        return pkg != null && pkg.startsWith("com.huawei.hms.");
    }

    /**
     * 单个组件解析失败不应该让整个 PackageInfo 变 null。
     *
     * <p>HMS 自检是 {@code getPackageInfo("com.huawei.hwid", GET_ACTIVITIES)}，
     * 只要有一个 Activity 的 resources / metaData 解析抛异常，以前整包就返回
     * null，HMS 判定「未安装」（日志里的 hms-absent）。现在坏一个跳一个，
     * 其余组件照常返回。
     */
    private static void logComponentFail(String pkg, String kind, String name, Throwable t) {
        com.hush.utils.Diag.log("pm", "组件解析失败 " + pkg + " " + kind + "=" + name + " " + t);
    }
}

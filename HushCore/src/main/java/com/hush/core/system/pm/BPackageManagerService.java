package com.hush.core.system.pm;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.RemoteException;
import android.text.TextUtils;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.hush.HushCore;
import com.hush.core.GmsCore;
import com.hush.core.env.BEnvironment;
import com.hush.core.system.BProcessManagerService;
import com.hush.core.system.ISystemService;
import com.hush.core.system.ProcessRecord;
import com.hush.core.system.user.BUserHandle;
import com.hush.core.system.user.BUserInfo;
import com.hush.core.system.user.BUserManagerService;
import com.hush.entity.pm.InstallOption;
import com.hush.entity.pm.InstallResult;
import com.hush.entity.pm.InstalledPackage;
import com.hush.utils.AbiUtils;
import com.hush.utils.FileUtils;
import com.hush.utils.PermissionUtils;
import com.hush.utils.Slog;
import com.hush.utils.compat.PackageParserCompat;
import com.hush.utils.compat.XposedParserCompat;

import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

/**
 * Created by @officialakshit01 on 4/1/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶 つ０
 * しーＪ
 * 此处无Bug
 */
public class BPackageManagerService extends IBPackageManagerService.Stub implements ISystemService {
    public static final String TAG = "BPackageManagerService";
    public static BPackageManagerService sService = new BPackageManagerService();
    private final Settings mSettings = new Settings(); // 等同于 PackageCacheManager
    private final ComponentResolver mComponentResolver;
    private static final BUserManagerService sUserManager = BUserManagerService.get();
    private final List<PackageMonitor> mPackageMonitors = new ArrayList<>();
    private final HashMap<String, BPackage.Permission> mPermissions = new HashMap<>();

    final Map<String, BPackageSettings> mPackages = mSettings.mPackages;
    private final Map<String, String[]> mDangerousPermissions = new HashMap<>();

    final Object mInstallLock = new Object();

    public static BPackageManagerService get() {
        return sService;
    }

    public BPackageManagerService() {
        mComponentResolver = new ComponentResolver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.PACKAGE_ADDED");
        filter.addAction("android.intent.action.PACKAGE_REMOVED");
        filter.addDataScheme("package");
        HushCore.getContext()
                .registerReceiver(mPackageChangedHandler, filter);
    }

    private final BroadcastReceiver mPackageChangedHandler = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (!TextUtils.isEmpty(action)) {
                if ("android.intent.action.PACKAGE_ADDED".equals(action)
                        || "android.intent.action.PACKAGE_REMOVED".equals(action)) {
                    mSettings.scanPackage();
                }
            }
        }
    };

    @Override
    public ApplicationInfo getApplicationInfo(String packageName, int flags, int userId) {
        if (!sUserManager.exists(userId))
            return null;
        if (Objects.equals(packageName, HushCore.getHostPkg())) {
            try {
                return HushCore.getPackageManager().getApplicationInfo(packageName, flags);
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
            return null;
        }
        flags = updateFlags(flags, userId);
        // reader
        synchronized (mPackages) {
            // Normalize package name to handle renamed packages and static libs
            BPackageSettings ps = mPackages.get(packageName);
            if (ps != null) {
                BPackage p = ps.pkg;
                return PackageManagerCompat.generateApplicationInfo(p, flags, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    @Override
    public ResolveInfo resolveService(Intent intent, int flags, String resolvedType, int userId) {
        if (!sUserManager.exists(userId))
            return null;
        List<ResolveInfo> query = queryIntentServicesInternal(
                intent, resolvedType, flags, userId);
        if (query != null) {
            if (query.size() >= 1) {
                // If there is more than one service with the same priority,
                // just arbitrarily pick the first one.
                return query.get(0);
            }
        }
        return null;
    }

    private List<ResolveInfo> queryIntentServicesInternal(Intent intent, String resolvedType, int flags, int userId) {
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }
        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<>(1);
            final ServiceInfo si = getServiceInfo(comp, flags, userId);
            if (si != null) {
                // When specifying an explicit component, we prevent the service from being
                // used when either 1) the service is in an instant application and the
                // caller is not the same instant application or 2) the calling package is
                // ephemeral and the activity is not visible to ephemeral applications.
                final ResolveInfo ri = new ResolveInfo();
                ri.serviceInfo = si;
                list.add(ri);
            }
            return list;
        }

        // reader
        synchronized (mPackages) {
            String pkgName = intent.getPackage();
            if (pkgName != null) {
                BPackageSettings bPackageSettings = mPackages.get(pkgName);
                if (bPackageSettings != null) {
                    final BPackage pkg = bPackageSettings.pkg;
                    return mComponentResolver.queryServices(intent, resolvedType, flags, pkg.services,
                            userId);
                }
            } else {
                return mComponentResolver.queryServices(intent, resolvedType, flags, userId);
            }
            return Collections.emptyList();
        }
    }

    @Override
    public ResolveInfo resolveActivity(Intent intent, int flags, String resolvedType, int userId) {
        if (!sUserManager.exists(userId))
            return null;
        List<ResolveInfo> resolves = queryIntentActivities(intent, resolvedType, flags, userId);
        return chooseBestActivity(intent, resolvedType, flags, resolves);
    }

    @Override
    public ProviderInfo resolveContentProvider(String authority, int flags, int userId) {
        if (!sUserManager.exists(userId))
            return null;
        return mComponentResolver.queryProvider(authority, flags, userId);
    }

    @Override
    public ResolveInfo resolveIntent(Intent intent, String resolvedType, int flags, int userId) {
        if (!sUserManager.exists(userId))
            return null;
        List<ResolveInfo> resolves = queryIntentActivities(intent, resolvedType, flags, userId);
        return chooseBestActivity(intent, resolvedType, flags, resolves);
    }

    private ResolveInfo chooseBestActivity(Intent intent, String resolvedType,
            int flags, List<ResolveInfo> query) {
        if (query != null) {
            final int N = query.size();
            if (N == 1) {
                return query.get(0);
            } else if (N > 1) {
                // If there is more than one activity with the same priority,
                // then let the user decide between them.
                ResolveInfo r0 = query.get(0);
                ResolveInfo r1 = query.get(1);
                // If the first activity has a higher priority, or a different
                // default, then it is always desirable to pick it.
                if (r0.priority != r1.priority
                        || r0.preferredOrder != r1.preferredOrder
                        || r0.isDefault != r1.isDefault) {
                    return query.get(0);
                }
            }
        }
        return null;
    }

    private List<ResolveInfo> queryIntentActivities(Intent intent,
            String resolvedType, int flags, int userId) {
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }

        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<>(1);
            final ActivityInfo ai = getActivity(comp, flags, userId);
            if (ai != null) {
                // When specifying an explicit component, we prevent the activity from being
                // used when either 1) the calling package is normal and the activity is within
                // an ephemeral application or 2) the calling package is ephemeral and the
                // activity is not visible to ephemeral applications.
                final ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                list.add(ri);
                return list;
            }
        }

        // reader
        synchronized (mPackages) {
            return mComponentResolver.queryActivities(intent, resolvedType, flags, userId);
        }
    }

    @Override
    public List<ResolveInfo> queryIntentServices(
            Intent intent, int flags, int userId) {
        final String resolvedType = intent.resolveTypeIfNeeded(HushCore.getContext().getContentResolver());
        return this.queryIntentServicesInternal(intent, resolvedType, flags, userId);
    }

    private ActivityInfo getActivity(ComponentName component, int flags,
            int userId) {
        flags = updateFlags(flags, userId);
        synchronized (mPackages) {
            BPackage.Activity a = mComponentResolver.getActivity(component);

            if (a != null) {
                BPackageSettings ps = mSettings.mPackages.get(component.getPackageName());
                if (ps == null)
                    return null;
                return PackageManagerCompat.generateActivityInfo(a, flags, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags, int userId) {
        if (!sUserManager.exists(userId))
            return null;
        if (Objects.equals(packageName, HushCore.getHostPkg())) {
            try {
                return HushCore.getPackageManager().getPackageInfo(packageName, flags);
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
            return null;
        }

        flags = updateFlags(flags, userId);
        BPackageSettings ps = null;
        // reader
        synchronized (mPackages) {
            // Normalize package name to handle renamed packages and static libs
            ps = mPackages.get(packageName);
        }
        if (ps != null) {
            PackageInfo pi = PackageManagerCompat.generatePackageInfo(ps, flags, ps.readUserState(userId), userId);
            if (pi == null) {
                // 解析某个组件时抛异常的话 PackageManagerCompat 会吞掉异常返回 null。
                // 已经装进空间的包绝不能回 null：HMS 自检、游戏探测都拿这个结果
                // 判断「有没有装」，一 null 就整条链路停摆（日志里的 hms-absent）。
                // 去掉组件数组再生成一次，至少把包名/版本/签名这些关键信息给出去。
                int safe = flags & ~(PackageManager.GET_ACTIVITIES
                        | PackageManager.GET_RECEIVERS
                        | PackageManager.GET_SERVICES
                        | PackageManager.GET_PROVIDERS);
                pi = PackageManagerCompat.generatePackageInfo(ps, safe, ps.readUserState(userId), userId);
                com.hush.utils.Diag.log("pm", "getPackageInfo 降级生成 " + packageName
                        + " flags=0x" + Integer.toHexString(flags) + " -> "
                        + (pi == null ? "null" : "ok"));
            }
            return pi;
        }
        return null;
    }

    @Override
    public ServiceInfo getServiceInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId))
            return null;
        synchronized (mPackages) {
            BPackage.Service s = mComponentResolver.getService(component);
            if (s != null) {
                BPackageSettings ps = mPackages.get(component.getPackageName());
                if (ps == null)
                    return null;
                return PackageManagerCompat.generateServiceInfo(
                        s, flags, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    @Override
    public ActivityInfo getReceiverInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId))
            return null;
        synchronized (mPackages) {
            BPackage.Activity a = mComponentResolver.getReceiver(component);
            if (a != null) {
                BPackageSettings ps = mPackages.get(component.getPackageName());
                if (ps == null)
                    return null;
                return PackageManagerCompat.generateActivityInfo(
                        a, flags, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    @Override
    public ActivityInfo getActivityInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId))
            return null;
        synchronized (mPackages) {
            BPackage.Activity a = mComponentResolver.getActivity(component);

            if (a != null) {
                BPackageSettings ps = mPackages.get(component.getPackageName());
                if (ps == null)
                    return null;
                return PackageManagerCompat.generateActivityInfo(
                        a, flags, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    @Override
    public ProviderInfo getProviderInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId))
            return null;
        synchronized (mPackages) {
            BPackage.Provider p = mComponentResolver.getProvider(component);
            if (p != null) {
                BPackageSettings ps = mPackages.get(component.getPackageName());
                if (ps == null)
                    return null;
                return PackageManagerCompat.generateProviderInfo(
                        p, flags, ps.readUserState(userId), userId);
            }
        }
        return null;
    }

    @Override
    public List<ApplicationInfo> getInstalledApplications(int flags, int userId) {
        return getInstalledApplicationsListInternal(flags, userId, Binder.getCallingUid());
    }

    @Override
    public List<PackageInfo> getInstalledPackages(int flags, int userId) {
        final int callingUid = Binder.getCallingUid();
        // if (getInstantAppPackageName(callingUid) != null) {
        // return ParceledListSlice.emptyList();
        // }
        if (!sUserManager.exists(userId))
            return Collections.emptyList();

        // writer
        synchronized (mPackages) {
            ArrayList<PackageInfo> list;
            list = new ArrayList<>(mPackages.size());
            for (BPackageSettings ps : mPackages.values()) {
                // if (filterSharedLibPackageLPr(ps, callingUid, userId, flags)) {
                // continue;
                // }
                // if (filterAppAccessLPr(ps, callingUid, userId)) {
                // continue;
                // }
                PackageInfo pi = getPackageInfo(ps.pkg.packageName, flags, userId);
                if (pi != null) {
                    list.add(pi);
                }
            }
            return new ArrayList<>(list);
        }
    }

    private List<ApplicationInfo> getInstalledApplicationsListInternal(int flags, int userId, int callingUid) {
        if (!sUserManager.exists(userId))
            return Collections.emptyList();
        // writer
        synchronized (mPackages) {
            ArrayList<ApplicationInfo> list;
            list = new ArrayList<>(mPackages.size());
            Collection<BPackageSettings> packageSettings = mPackages.values();
            for (BPackageSettings ps : packageSettings) {
                ApplicationInfo ai = PackageManagerCompat.generateApplicationInfo(ps.pkg, flags,
                        ps.readUserState(userId), userId);
                if (ai != null) {
                    list.add(ai);
                }
            }
            return list;
        }
    }

    @Override
    public List<ResolveInfo> queryIntentActivities(Intent intent, int flags, String resolvedType, int userId)
            throws RemoteException {
        if (!sUserManager.exists(userId))
            return Collections.emptyList();
        final String pkgName = intent.getPackage();
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }

        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<>(1);
            final ActivityInfo ai = getActivityInfo(comp, flags, userId);
            if (ai != null) {
                // When specifying an explicit component, we prevent the activity from being
                // used when either 1) the calling package is normal and the activity is within
                // an ephemeral application or 2) the calling package is ephemeral and the
                // activity is not visible to ephemeral applications.
                final ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                list.add(ri);
            }
            return list;
        }

        // reader
        List<ResolveInfo> result;
        synchronized (mPackages) {
            if (pkgName != null) {
                BPackageSettings bPackageSettings = mPackages.get(pkgName);
                result = null;
                if (bPackageSettings != null) {
                    final BPackage pkg = bPackageSettings.pkg;

                    result = mComponentResolver.queryActivities(
                            intent, resolvedType, flags, pkg.activities, userId);
                }
                if (result == null || result.size() == 0) {
                    // the caller wants to resolve for a particular package; however, there
                    // were no installed results, so, try to find an ephemeral result
                    if (result == null) {
                        result = new ArrayList<>();
                    }
                }
                return result;
            }
        }
        return Collections.emptyList();
    }

    @Override
    public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags, String resolvedType, int userId)
            throws RemoteException {
        if (!sUserManager.exists(userId))
            return Collections.emptyList();

        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }
        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<>(1);
            final ActivityInfo ai = getReceiverInfo(comp, flags, userId);
            if (ai != null) {
                // When specifying an explicit component, we prevent the activity from being
                // used when either 1) the calling package is normal and the activity is within
                // an instant application or 2) the calling package is ephemeral and the
                // activity is not visible to instant applications.
                ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                list.add(ri);
            }
            return list;
        }

        // reader
        synchronized (mPackages) {
            String pkgName = intent.getPackage();
            BPackageSettings bPackageSettings = mPackages.get(pkgName);
            if (bPackageSettings != null) {
                final BPackage pkg = bPackageSettings.pkg;
                return mComponentResolver.queryReceivers(
                        intent, resolvedType, flags, pkg.receivers, userId);
            } else {
                return mComponentResolver.queryReceivers(intent, resolvedType, flags, userId);
            }
        }
    }

    @Override
    public List<ProviderInfo> queryContentProviders(String processName, int uid, int flags, int userId)
            throws RemoteException {
        if (!sUserManager.exists(userId))
            return Collections.emptyList();

        List<ProviderInfo> providers = new ArrayList<>();
        if (TextUtils.isEmpty(processName))
            return providers;
        providers.addAll(mComponentResolver.queryProviders(processName, null, flags, userId));
        return providers;
    }

    @Override
    public InstallResult installPackageAsUser(String file, InstallOption option, int userId) {
        synchronized (mInstallLock) {
            return installPackageAsUserLocked(file, option, userId);
        }
    }

    @Override
    public void uninstallPackageAsUser(String packageName, int userId) throws RemoteException {
        synchronized (mInstallLock) {
            synchronized (mPackages) {
                BPackageSettings ps = mPackages.get(packageName);
                if (ps == null)
                    return;
                if (ps.installOption.isFlag(InstallOption.FLAG_XPOSED) && userId != BUserHandle.USER_XPOSED) {
                    return;
                }
                if (!isInstalled(packageName, userId)) {
                    return;
                }
                boolean removeApp = ps.getUserState().size() <= 1;
                BProcessManagerService.get().killPackageAsUser(packageName, userId);
                int i = BPackageInstallerService.get().uninstallPackageAsUser(ps, removeApp, userId);
                if (i < 0) {
                    // todo
                }

                if (removeApp) {
                    mSettings.removePackage(packageName);
                    mComponentResolver.removeAllComponents(ps.pkg);
                } else {
                    ps.removeUser(userId);
                    ps.save();
                }
                onPackageUninstalled(packageName, removeApp, userId);
                // 卸载 HMS 后同理：别的应用还缓存着「HMS 可用」，不重启会
                // 继续往一个已经不存在的服务上绑。
                if (com.hush.core.HmsCore.HMS_PKG.equals(packageName)) {
                    restartOtherApps(userId, packageName);
                }
            }
        }
    }

    @Override
    public void uninstallPackage(String packageName) {
        synchronized (mInstallLock) {
            synchronized (mPackages) {
                BPackageSettings ps = mPackages.get(packageName);
                if (ps == null)
                    return;
                BProcessManagerService.get().killAllByPackageName(packageName);
                if (ps.installOption.isFlag(InstallOption.FLAG_XPOSED)) {
                    for (BUserInfo user : BUserManagerService.get().getAllUsers()) {
                        int i = BPackageInstallerService.get().uninstallPackageAsUser(ps, true, user.id);
                        if (i < 0) {
                            continue;
                        }
                        onPackageUninstalled(packageName, true, user.id);
                    }
                } else {
                    for (Integer userId : ps.getUserIds()) {
                        int i = BPackageInstallerService.get().uninstallPackageAsUser(ps, true, userId);
                        if (i < 0) {
                            continue;
                        }
                        onPackageUninstalled(packageName, true, userId);
                    }
                }
                mSettings.removePackage(packageName);
                mComponentResolver.removeAllComponents(ps.pkg);
            }
        }
    }

    @Override
    public void clearPackage(String packageName, int userId) {
        if (!isInstalled(packageName, userId)) {
            return;
        }
        BProcessManagerService.get().killPackageAsUser(packageName, userId);
        BPackageSettings ps = mPackages.get(packageName);
        if (ps == null)
            return;
        int i = BPackageInstallerService.get().clearPackage(ps, userId);
    }

    @Override
    public void stopPackage(String packageName, int userId) {
        BProcessManagerService.get().killPackageAsUser(packageName, userId);
    }

    @Override
    public boolean isAppRunning(String packageName, int userId) {
        ActivityManager activityManager = (ActivityManager) HushCore.getContext()
                .getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
        if (processes == null)
            return false;

        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (Arrays.asList(process.pkgList).contains(packageName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void deleteUser(int userId) throws RemoteException {
        synchronized (mPackages) {
            for (BPackageSettings ps : mPackages.values()) {
                uninstallPackageAsUser(ps.pkg.packageName, userId);
            }
        }
    }

    @Override
    public boolean isInstalled(String packageName, int userId) {
        if (!sUserManager.exists(userId))
            return false;
        synchronized (mPackages) {
            BPackageSettings ps = mPackages.get(packageName);
            if (ps == null)
                return false;
            return ps.getInstalled(userId);
        }
    }

    @Override
    public List<InstalledPackage> getInstalledPackagesAsUser(int userId) {
        if (!sUserManager.exists(userId))
            return Collections.emptyList();
        synchronized (mPackages) {
            List<InstalledPackage> installedPackages = new ArrayList<>();
            for (BPackageSettings ps : mPackages.values()) {
                if (ps.getInstalled(userId)) {
                    InstalledPackage installedPackage = new InstalledPackage();
                    installedPackage.userId = userId;
                    installedPackage.packageName = ps.pkg.packageName;
                    installedPackages.add(installedPackage);
                }
            }
            return installedPackages;
        }
    }

    @Override
    public String[] getPackagesForUid(int uid, int userId) throws RemoteException {
        if (!sUserManager.exists(userId))
            return new String[] {};
        synchronized (mPackages) {
            List<String> packages = new ArrayList<>();
            for (BPackageSettings ps : mPackages.values()) {
                String packageName = ps.pkg.packageName;
                if (ps.getInstalled(userId) && getAppId(packageName) == uid) {
                    packages.add(packageName);
                }
            }
            if (packages.isEmpty()) {
                // 2.6.38：HMS 判断「有没有网」走的是这一串（反编译 hmscore 确认）：
                //   Context.checkPermission(ACCESS_NETWORK_STATE, pid, uid)
                //   → getPackageManager().getPackagesForUid(uid)
                //   → AppOpsManager.unsafeCheckOpNoThrow(op, uid, pkg)
                //   → getSystemService("connectivity").getActiveNetwork()
                // 容器里 uid 永远是宿主 uid，跟 guest 包的 appId 对不上，这里以前
                // 返回空数组，HMS 直接打 "no network permission!" 并返回 false，
                // 页面就显示「无网络」。2.6.33 放行 checkPermission 没用，因为
                // 真正卡住的是这一句。
                // 主线程调用时 Binder.getCallingPid() 是 0，findProcessByPid 拿不到，
                // 所以还要按 uid 兜一层。
                ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(getCallingPid());
                if (processByPid != null) {
                    packages.add(processByPid.getPackageName());
                } else if (uid == android.os.Process.myUid()) {
                    packages.add(HushCore.get().getHostPackageName());
                } else {
                    String cur = com.hush.app.BActivityThread.getAppPackageName();
                    if (cur != null && cur.length() > 0) packages.add(cur);
                }
                if (packages.isEmpty()) packages.add("com.hush.space");
                Slog.d("BPMS", "getPackagesForUid uid=" + uid + " -> " + packages);
            }
            return packages.toArray(new String[] {});
        }
    }

    public int checkUidPermission(String permission, int uid, String packageName) throws RemoteException {
        // 2.6.33：AMS.checkPermission 走的就是这里，网络类 normal 权限直接放行。
        if (isNormalNetPermission(permission)) {
            return PackageManager.PERMISSION_GRANTED;
        }
        PermissionInfo info = getPermissionInfo(permission, 0);
        if (info != null) {
            return 0;
        }
        return HushCore.getPackageManager().checkPermission(permission, packageName);
    }

    @Override
    public int checkPermission(String permName, String pkgName, int userId) throws RemoteException {
        if ("android.permission.INTERACT_ACROSS_USERS".equals(permName)
                || "android.permission.INTERACT_ACROSS_USERS_FULL".equals(permName)) {
            return PackageManager.PERMISSION_DENIED;
        }
        // 2.6.33：normal 级网络权限必须放行。
        // 容器里 mPermissions 从未被填充（全仓库没有任何 put），getPermissionInfo
        // 永远返回 null，于是每一条普通权限都退到宿主 PM 按**虚拟包名**查询，
        // 宿主里根本没有这个包，结果一律 DENIED。
        // HMS 的 NetworkUtil 用 checkSelfPermission(ACCESS_NETWORK_STATE) 做
        // 「有没有网」的第一道判断，被拒后直接显示「没有网络」，真实网络都不探。
        if (isNormalNetPermission(permName)) {
            return PackageManager.PERMISSION_GRANTED;
        }
        PermissionInfo permissionInfo = getPermissionInfo(permName, 0);
        if (permissionInfo != null) {
            return PackageManager.PERMISSION_GRANTED;
        }
        // 2.6.45：guest 自己在清单里声明过的危险权限一律放行。
        // 容器没有运行时授权流程，下面的宿主查询按**虚拟包名**进行，
        // 宿主里没有 com.huawei.hwid 这个包，结果永远 DENIED。
        // HMS 的 PermissionUtils 会先 checkSelfPermission(READ_PHONE_STATE)，
        // 被拒就不去取 IMEI/SN，deviceID 变成空串，服务端
        // /IDM/getSMSCodeV3 直接回 70001201 Parameter[deviceID] is invalid。
        String[] declared = getDangerousPermissions(pkgName);
        if (declared != null) {
            for (String p : declared) {
                if (p.equals(permName)) {
                    return PackageManager.PERMISSION_GRANTED;
                }
            }
        }
        return HushCore.getPackageManager().checkPermission(permName, pkgName);
    }

    /** 安装即授予的 normal 级网络权限，容器里没有权限表，只能按名单放行。 */
    static boolean isNormalNetPermission(String perm) {
        if (perm == null) return false;
        switch (perm) {
            case "android.permission.ACCESS_NETWORK_STATE":
            case "android.permission.INTERNET":
            case "android.permission.ACCESS_WIFI_STATE":
            case "android.permission.CHANGE_NETWORK_STATE":
            case "android.permission.CHANGE_WIFI_STATE":
            case "android.permission.ACCESS_NETWORK_CONDITIONS":
                return true;
            default:
                return false;
        }
    }

    @Override
    public PermissionInfo getPermissionInfo(String name, int flags) throws RemoteException {
        synchronized (mPackages) {
            BPackage.Permission p = mPermissions.get(name);
            if (p != null) {
                return new PermissionInfo(p.info);
            }
        }
        return null;
    }

    private InstallResult installPackageAsUserLocked(String file, InstallOption option, int userId) {
        long l = System.currentTimeMillis();
        InstallResult result = new InstallResult();
        File apkFile = null;
        try {
            if (!sUserManager.exists(userId)) {
                sUserManager.createUser(userId);
            }
            if (option.isFlag(InstallOption.FLAG_URI_FILE)) {
                apkFile = new File(BEnvironment.getCacheDir(), UUID.randomUUID().toString() + ".apk");
                InputStream inputStream = HushCore.getContext().getContentResolver().openInputStream(Uri.parse(file));
                FileUtils.copyFile(inputStream, apkFile);
            } else {
                apkFile = new File(file);
            }

            if (option.isFlag(InstallOption.FLAG_XPOSED) && userId != BUserHandle.USER_XPOSED) {
                return new InstallResult().installError("Please install the XP module in XP module management");
            }
            if (option.isFlag(InstallOption.FLAG_XPOSED) && !XposedParserCompat.isXPModule(apkFile.getAbsolutePath())) {
                return new InstallResult().installError("not a XP module");
            }

            PackageInfo packageArchiveInfo = HushCore.getPackageManager()
                    .getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            if (packageArchiveInfo == null) {
                return result.installError("getPackageArchiveInfo error.Please check whether APK is normal.");
            }

            boolean support = AbiUtils.isSupport(apkFile);
            if (!support) {
                String msg = packageArchiveInfo.applicationInfo.loadLabel(HushCore.getPackageManager()) + "["
                        + packageArchiveInfo.packageName + "]";
                return result.installError(packageArchiveInfo.packageName,
                        msg + (HushCore.is64Bit() ? " not support armeabi-v7a abi" : "not support arm64-v8a abi"));
            }
            PackageParser.Package aPackage = parserApk(apkFile.getAbsolutePath());
            if (aPackage == null) {
                return result.installError("parser apk error.");
            }
            result.packageName = aPackage.packageName;

            if (option.isFlag(InstallOption.FLAG_SYSTEM)) {
                aPackage.applicationInfo = HushCore.getPackageManager().getPackageInfo(aPackage.packageName,
                        0).applicationInfo;
            }
            BPackageSettings bPackageSettings = mSettings.getPackageLPw(aPackage.packageName, aPackage, option);

            // stop pkg
            BProcessManagerService.get().killPackageAsUser(aPackage.packageName, userId);

            int i = BPackageInstallerService.get().installPackageAsUser(bPackageSettings, userId);
            if (i < 0) {
                return result.installError("install apk error.");
            }
            synchronized (mPackages) {
                bPackageSettings.setInstalled(true, userId);
                bPackageSettings.save();
            }
            mComponentResolver.removeAllComponents(bPackageSettings.pkg);
            mComponentResolver.addAllComponents(bPackageSettings.pkg);
            mSettings.scanPackage(aPackage.packageName);
            onPackageInstalled(bPackageSettings.pkg.packageName, userId);

            // 2.6.57：装完 HMS 不再批量杀空间里的其它 App。
            // 以前这里会把同一空间里所有 guest 进程全部 kill 一遍，用户
            // 正在用的游戏/账号页会整片消失，看起来就是「装 HMS 必闪退」。
            // 缓存问题交给下面这行 + 进程下次冷启动自然失效。
            if (com.hush.core.HmsCore.HMS_PKG.equals(aPackage.packageName)) {
                Slog.d(TAG, "HMS installed; skip mass restart of user " + userId
                        + " to avoid closing running apps");
            }
            return result;
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (apkFile != null && option.isFlag(InstallOption.FLAG_URI_FILE)) {
                FileUtils.deleteDir(apkFile);
            }
            Slog.d(TAG, "install finish: " + (System.currentTimeMillis() - l) + "ms");
        }
        return result;
    }

    /**
     * 结束同一空间内除 {@code exceptPackage} 外的所有 guest 进程。
     *
     * <p>只用于 HMS Core 安装完成：它的可用性被调用方按进程缓存，必须让
     * 游戏重新走一遍 PackageManager 查询，否则会一直认为它没装。
     */
    private void restartOtherApps(int userId, String exceptPackage) {
        try {
            for (ProcessRecord record : BProcessManagerService.get().getProcessesAsUser(userId)) {
                String pkg = record.getPackageName();
                if (pkg == null || pkg.equals(exceptPackage)) continue;
                BProcessManagerService.get().killPackageAsUser(pkg, userId);
                Slog.d(TAG, "HMS installed, restart " + pkg + " in user " + userId);
            }
        } catch (Throwable t) {
            Slog.w(TAG, "restart apps after HMS install failed", t);
        }
    }

    private PackageParser.Package parserApk(String file) {
        try {
            File parseTarget = new File(file);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                if (parseTarget.getName().equals("base.apk")) {
                    parseTarget = parseTarget.getParentFile();
                }
            }
            PackageParser parser = PackageParserCompat.createParser(parseTarget);
            PackageParser.Package aPackage = PackageParserCompat.parsePackage(parser, parseTarget, 0);
            PackageParserCompat.collectCertificates(parser, aPackage, 0);
            return aPackage;
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return null;
    }

    static String fixProcessName(String defProcessName, String processName) {
        if (processName == null) {
            return defProcessName;
        }
        return processName;
    }

    /**
     * Update given flags based on encryption status of current user.
     */
    private int updateFlags(int flags, int userId) {
        if ((flags & (PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                | PackageManager.MATCH_DIRECT_BOOT_AWARE)) != 0) {
            // Caller expressed an explicit opinion about what encryption
            // aware/unaware components they want to see, so fall through and
            // give them what they want
        } else {
            // Caller expressed no opinion, so match based on user state
            flags |= PackageManager.MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE;
        }
        return flags;
    }

    public int getAppId(String packageName) {
        BPackageSettings bPackageSettings = mPackages.get(packageName);
        if (bPackageSettings != null)
            return bPackageSettings.appId;
        return -1;
    }

    Settings getSettings() {
        return mSettings;
    }

    public void addPackageMonitor(PackageMonitor monitor) {
        mPackageMonitors.add(monitor);
    }

    public void removePackageMonitor(PackageMonitor monitor) {
        mPackageMonitors.remove(monitor);
    }

    void onPackageUninstalled(String packageName, boolean isRemove, int userId) {
        for (PackageMonitor packageMonitor : mPackageMonitors) {
            packageMonitor.onPackageUninstalled(packageName, isRemove, userId);
        }
        Slog.d(TAG, "onPackageUninstalled: " + packageName + ", userId: " + userId);
    }

    void onPackageInstalled(String packageName, int userId) {
        for (PackageMonitor packageMonitor : mPackageMonitors) {
            packageMonitor.onPackageInstalled(packageName, userId);
        }
        Slog.d(TAG, "onPackageInstalled: " + packageName + ", userId: " + userId);
    }

    public BPackageSettings getBPackageSetting(String packageName) {
        return mPackages.get(packageName);
    }

    public List<BPackageSettings> getBPackageSettings() {
        return new ArrayList<>(mPackages.values());
    }

    @Override
    public void systemReady() {
        mSettings.scanPackage();
        for (BPackageSettings value : mPackages.values()) {
            mComponentResolver.removeAllComponents(value.pkg);
            mComponentResolver.addAllComponents(value.pkg);
        }
    }

    // 20240801 add request permission add start 0
    public String[] getDangerousPermissions(String packageName) {
        synchronized (mDangerousPermissions) {
            return mDangerousPermissions.get(packageName);
        }
    }

    public void analyzePackageLocked(BPackageSettings bPackageSettings) {
        synchronized (mDangerousPermissions) {
            mDangerousPermissions.put(bPackageSettings.pkg.packageName,
                    PermissionUtils.findDangerousPermissions(bPackageSettings.pkg.requestedPermissions));
        }
    }
    // 20240801 add request permission add end 0
}
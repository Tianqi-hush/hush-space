package com.hush.fake.frameworks;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Debug;
import android.os.RemoteException;

import java.util.Collections;
import java.util.List;

import com.hush.HushCore;
import com.hush.app.BActivityThread;
import com.hush.core.system.ServiceManager;
import com.hush.core.system.api.MetaActivationManager;
import com.hush.core.system.pm.BPackage;
import com.hush.core.system.pm.IBPackageManagerService;
import com.hush.entity.pm.InstallOption;
import com.hush.entity.pm.InstallResult;
import com.hush.entity.pm.InstalledPackage;
import org.lsposed.lsparanoid.Obfuscate;

@Obfuscate
public class BPackageManager extends BlackManager<IBPackageManagerService> {
    private static final BPackageManager sPackageManager = new BPackageManager();

    public static BPackageManager get() {
        return sPackageManager;
    }

    @Override
    protected String getServiceName() {
        return ServiceManager.PACKAGE_MANAGER;
    }
    
    private String isSystemApps() {
        return "ALLOW";
    }

    public Intent getLaunchIntentForPackage(String packageName, int userId) {
        if ("BLOCK".equals(isSystemApps())) return null;
        Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
        intentToResolve.addCategory(Intent.CATEGORY_INFO);
        intentToResolve.setPackage(packageName);
        List<ResolveInfo> ris = queryIntentActivities(intentToResolve,0,intentToResolve.resolveTypeIfNeeded(HushCore.getContext().getContentResolver()),userId);

        // Otherwise, try to find a main launcher activity.
        if (ris == null || ris.size() <= 0) {
            // reuse the intent instance
            intentToResolve.removeCategory(Intent.CATEGORY_INFO);
            intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER);
            intentToResolve.setPackage(packageName);
            ris = queryIntentActivities(intentToResolve,0,intentToResolve.resolveTypeIfNeeded(HushCore.getContext().getContentResolver()),userId);
        }
        if (ris == null || ris.size() <= 0) {
            // HMS Core 6.16 的清单里<b>没有</b> LAUNCHER activity —— 真机上
            // 它根本没有桌面图标，只能从「设置 → 华为账号」进入
            // （{@code com.android.settings.action.IA_SETTINGS}）。
            //
            // 容器里用户在空间里点 HMS Core 图标时，走的就是这个方法。
            // 返回 null 的表现就是「点了没反应 / 打不开」。
            //
            // 给的是账号 kit 里的登录引导页：它是真正能打开的界面
            // （2.6.16 日志里 StartUpGuideLoginActivity onCreate 正常），
            // 而且资源和类都在 kit 自己包里，不用跨包共享资源。
            // kit 还没补装时才退回 HMS 本体的声明页，至少不会是「点了没反应」。
            if (com.hush.core.HmsCore.HMS_PKG.equals(packageName)) {
                Intent hms = new Intent(Intent.ACTION_MAIN);
                boolean hasKit = false;
                try {
                    hasKit = HushCore.get().isInstalled(
                            com.hush.core.HmsCore.ACCOUNT_KIT_PKG, userId);
                } catch (Throwable ignored) {
                }
                // 真机上「设置 → 华为账号」进的正是这个页面：
                // com.huawei.hwid.cloudsettings.ui.HuaweiIDForSettingsActivity，
                // 进程 com.huawei.hwid.container2，声明了
                // com.android.settings.action.IA_SETTINGS。
                //
                // 2.6.18 用的是账号 kit 的 StartUpGuideLoginActivity，
                // 那是「登录引导页」：从 HMS 自己进来时它判断不需要引导，
                // 直接 finish —— 用户看到的就是「界面出来 1-2 秒然后闪退」。
                hms.setClassName(packageName,
                        "com.huawei.hwid.cloudsettings.ui.HuaweiIDForSettingsActivity");
                // 从 HMS Core 图标进来，调用方就是 HMS 自己。不写这一条的话
                // 账号页拿到的是空调用方，ForegroundBus 会判成非法调用。
                hms.putExtra("_G_|_caller_pkg_", com.hush.core.HmsCore.HMS_PKG);
                hms.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                com.hush.utils.Diag.log("pm", "HMS 无 launcher，入口=" + hms.getComponent()
                        + " hasKit=" + hasKit);
                return hms;
            }
            return null;
        }
        Intent intent = new Intent(intentToResolve);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(ris.get(0).activityInfo.packageName,ris.get(0).activityInfo.name);
        return intent;
    }

    public ResolveInfo resolveService(Intent intent, int flags, String resolvedType, int userId) {
        try {
            return getService().resolveService(intent, flags, resolvedType, userId);
        } catch (RemoteException e) {
            crash(e);
        }
        return null;
    }

    public ResolveInfo resolveActivity(Intent intent, int flags, String resolvedType, int userId) {
        try {
            return getService().resolveActivity(intent, flags, resolvedType, userId);
        } catch (RemoteException e) {
            crash(e);
        }
        return null;
    }

    public ProviderInfo resolveContentProvider(String authority, int flags, int userId) {
        try {
            return getService().resolveContentProvider(authority, flags, userId);
        } catch (RemoteException e) {
            crash(e);
        }
        return null;
    }

    public ResolveInfo resolveIntent(Intent intent, String resolvedType, int flags, int userId) {
        try {
            return getService().resolveIntent(intent, resolvedType, flags, userId);
        } catch (RemoteException e) {
            crash(e);
        }
        return null;
    }

    public PermissionInfo getPermissionInfo(String name, int flags){
        try {
            return getService().getPermissionInfo(name,flags);
        } catch (RemoteException e) {
            crash(e);
        }
        return null;
    }

    public ApplicationInfo getApplicationInfo(String packageName, int flags, int userId) {
        try {
            return getService().getApplicationInfo(packageName, flags, userId);
        } catch (RemoteException e) {
            crash(e);
        }
        return null;
    }

    public PackageInfo getPackageInfo(String packageName, int flags, int userId) {
        try {
            return getService().getPackageInfo(packageName, flags, userId);
        } catch (RemoteException e) {
            crash(e);
        }
        return null;
    }

    public ServiceInfo getServiceInfo(ComponentName component, int flags, int userId) {
        try {
            return getService().getServiceInfo(component, flags, userId);
        } catch (RemoteException e) {
            crash(e);
        }
        return null;
    }

    public ActivityInfo getReceiverInfo(ComponentName componentName, int flags, int userId) {
        try {
            return getService().getReceiverInfo(componentName, flags, userId);
        } catch (RemoteException e) {
            crash(e);
        }
        return null;
    }

    public ActivityInfo getActivityInfo(ComponentName component, int flags, int userId) {
        try {
            return getService().getActivityInfo(component, flags, userId);
        } catch (RemoteException e) {
            crash(e);
        }
        return null;
    }

    public ProviderInfo getProviderInfo(ComponentName component, int flags, int userId) {
        try {
            return getService().getProviderInfo(component, flags, userId);
        } catch (RemoteException e) {
            crash(e);
        }
        return null;
    }

    public List<ResolveInfo> queryIntentActivities(Intent intent, int flags, String resolvedType, int userId) {
        try {
            return getService().queryIntentActivities(intent, flags, resolvedType, userId);
        } catch (RemoteException e) {
            crash(e);
        }
        return null;
    }

    public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags, String resolvedType, int userId) {
        try {
            return getService().queryBroadcastReceivers(intent, flags, resolvedType, userId);
        } catch (RemoteException e) {
            crash(e);
        }
        return null;
    }

    public List<ProviderInfo> queryContentProviders(String processName, int uid, int flags, int userId) {
        try {
            return getService().queryContentProviders(processName, uid, flags, userId);
        } catch (RemoteException e) {
            crash(e);
        }
        return null;
    }

    public InstallResult installPackageAsUser(String file, InstallOption option, int userId) {
        if ("BLOCK".equals(isSystemApps())) return new InstallResult(false, "sdk not activated");
        try {
            return getService().installPackageAsUser(file, option, userId);
        } catch (RemoteException e) {
            crash(e);
        }
        return null;
    }

    public List<ApplicationInfo> getInstalledApplications(int flags, int userId) {
        try {
            return getService().getInstalledApplications(flags, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return Collections.emptyList();
    }

    public List<PackageInfo> getInstalledPackages(int flags, int userId) {
        try {
            return getService().getInstalledPackages(flags, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return Collections.emptyList();
    }

    public void clearPackage(String packageName, int userId) {
    //    if ("BLOCK".equals(isSystemApps())) return;
        try {
            getService().clearPackage(packageName, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void stopPackage(String packageName, int userId) {
      //  if ("BLOCK".equals(isSystemApps())) return;
        try {
            getService().stopPackage(packageName, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public boolean isAppRunning(String packageName, int userId) {
        try {
            return getService().isAppRunning(packageName, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
            return false;
        }
    }

    public void uninstallPackageAsUser(String packageName, int userId) {
     //   if ("BLOCK".equals(isSystemApps())) return;
        try {
            getService().uninstallPackageAsUser(packageName, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void uninstallPackage(String packageName) {
     //   if ("BLOCK".equals(isSystemApps())) return;
        try {
            getService().uninstallPackage(packageName);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public boolean isInstalled(String packageName, int userId) {
        try {
            return getService().isInstalled(packageName, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return false;
    }

    public List<InstalledPackage> getInstalledPackagesAsUser(int userId) {
        try {
            return getService().getInstalledPackagesAsUser(userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return Collections.emptyList();
    }

    public String[] getPackagesForUid(int uid) {
        try {
            return getService().getPackagesForUid(uid, BActivityThread.getUserId());
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return new String[]{};
    }

    public int checkPermission(String permName, String pkgName, int userId) {
        try {
            return getService().checkPermission(permName, pkgName,userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private void crash(Throwable e) {
        e.printStackTrace();
    }
}

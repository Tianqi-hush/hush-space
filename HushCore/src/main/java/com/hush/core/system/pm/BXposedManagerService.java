package com.hush.core.system.pm;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Parcel;

import androidx.core.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hush.core.env.BEnvironment;
import com.hush.core.system.ISystemService;
import com.hush.core.system.user.BUserHandle;
import com.hush.entity.pm.InstalledModule;
import com.hush.entity.pm.XposedConfig;
import com.hush.utils.CloseUtils;
import com.hush.utils.FileUtils;
import com.hush.utils.compat.XposedParserCompat;

/**
 * Created by @officialakshit01 on 5/2/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶 つ０
 * しーＪ
 * 此处无Bug
 */
public class BXposedManagerService extends IBXposedManagerService.Stub implements ISystemService, PackageMonitor {
    private static final BXposedManagerService sService = new BXposedManagerService();

    private XposedConfig mXposedConfig;
    private final Object mLock = new Object();
    private BPackageManagerService mPms;
    private final Map<String, InstalledModule> mCacheModule = new HashMap<>();

    public static BXposedManagerService get() {
        return sService;
    }

    public BXposedManagerService() {
    }

    @Override
    public void systemReady() {
        loadModuleStateLr();
        mPms = BPackageManagerService.get();
        mPms.addPackageMonitor(this);
    }

    @Override
    public boolean isXPEnable() {
        synchronized (mLock) {
            return mXposedConfig.enable;
        }
    }

    @Override
    public void setXPEnable(boolean enable) {
        synchronized (mLock) {
            mXposedConfig.enable = enable;
            saveModuleStateLw();
        }
    }

    @Override
    public boolean isModuleEnable(String packageName) {
        synchronized (mLock) {
            Boolean enable = mXposedConfig.moduleState.get(packageName);
            return enable != null && enable;
        }
    }

    @Override
    public void setModuleEnable(String packageName, boolean enable) {
        synchronized (mLock) {
            if (!mPms.isInstalled(packageName, BUserHandle.USER_XPOSED)) {
                return;
            }
            mXposedConfig.moduleState.put(packageName, enable);
            saveModuleStateLw();
        }
    }

    @Override
    public List<InstalledModule> getInstalledModules() {
        List<ApplicationInfo> installedApplications = mPms.getInstalledApplications(FileUtils.FileMode.MODE_IWUSR,
                BUserHandle.USER_XPOSED);
        synchronized (mCacheModule) {
            for (ApplicationInfo installedApplication : installedApplications) {
                if (mCacheModule.containsKey(installedApplication.packageName))
                    continue;
                InstalledModule installedModule = XposedParserCompat.parseModule(installedApplication);
                if (installedModule != null) {
                    mCacheModule.put(installedApplication.packageName, installedModule);
                }
            }
            ArrayList<InstalledModule> installedModules = new ArrayList<>(mCacheModule.values());
            for (InstalledModule installedModule : installedModules) {
                installedModule.enable = isModuleEnable(installedModule.packageName);
                installedModule.scope = getModuleScope(installedModule.packageName);
            }
            return installedModules;
        }
    }

    @Override
    public List<String> getModuleScope(String packageName) {
        synchronized (mLock) {
            List<String> scope = mXposedConfig.moduleScope.get(packageName);
            // 空 = 全部作用域，调用方按「不限制」处理。
            return scope == null ? new ArrayList<String>() : new ArrayList<>(scope);
        }
    }

    @Override
    public void setModuleScope(String packageName, List<String> scope) {
        synchronized (mLock) {
            if (scope == null || scope.isEmpty()) {
                // 恢复默认：全部应用。
                mXposedConfig.moduleScope.remove(packageName);
            } else {
                mXposedConfig.moduleScope.put(packageName,
                        new ArrayList<>(new java.util.LinkedHashSet<>(scope)));
            }
            saveModuleStateLw();
        }
    }

    private void loadModuleStateLr() {
        File xpModuleConf = BEnvironment.getXPModuleConf();
        if (!xpModuleConf.exists()) {
            mXposedConfig = new XposedConfig();
            saveModuleStateLw();
            return;
        }
        Parcel parcel = null;
        try {
            parcel = FileUtils.readToParcel(xpModuleConf);
            mXposedConfig = new XposedConfig(parcel);
        } catch (Exception e) {
            e.printStackTrace();
            // 文件损坏/被截断时不能留 null，否则后面每次调用都是 NPE。
            mXposedConfig = new XposedConfig();
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }

    private void saveModuleStateLw() {
        Parcel parcel = Parcel.obtain();
        AtomicFile atomicFile = new AtomicFile(BEnvironment.getXPModuleConf());
        FileOutputStream fileOutputStream = null;
        try {
            mXposedConfig.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            fileOutputStream = atomicFile.startWrite();
            FileUtils.writeParcelToOutput(parcel, fileOutputStream);
            atomicFile.finishWrite(fileOutputStream);
        } catch (Exception ignored) {
            atomicFile.failWrite(fileOutputStream);
        } finally {
            parcel.recycle();
            CloseUtils.close(fileOutputStream);
        }
    }

    @Override
    public void onPackageUninstalled(String packageName, boolean removeApp, int userId) {
        if (userId != BUserHandle.USER_XPOSED && userId != BUserHandle.USER_ALL) {
            return;
        }
        synchronized (mCacheModule) {
            mCacheModule.remove(packageName);
        }
        synchronized (mLock) {
            mXposedConfig.moduleState.remove(packageName);
            mXposedConfig.moduleScope.remove(packageName);
            saveModuleStateLw();
        }
    }

    @Override
    public void onPackageInstalled(String packageName, int userId) {
        if (userId != BUserHandle.USER_XPOSED && userId != BUserHandle.USER_ALL) {
            return;
        }
        synchronized (mCacheModule) {
            mCacheModule.remove(packageName);
        }
        synchronized (mLock) {
            mXposedConfig.moduleState.put(packageName, false);
            // 新装/重装模块：作用域回到「全部应用」。
            mXposedConfig.moduleScope.remove(packageName);
            saveModuleStateLw();
        }
    }
}

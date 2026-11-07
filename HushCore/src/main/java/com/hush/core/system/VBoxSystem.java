package com.hush.core.system;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.hush.HushCore;
import com.hush.core.env.AppSystemEnv;
import com.hush.core.env.BEnvironment;
import com.hush.core.system.accounts.BAccountManagerService;
import com.hush.core.system.am.BActivityManagerService;
import com.hush.core.system.am.BJobManagerService;
import com.hush.core.system.location.BLocationManagerService;
import com.hush.core.system.notification.BNotificationManagerService;
import com.hush.core.system.os.BStorageManagerService;
import com.hush.core.system.pm.BPackageInstallerService;
import com.hush.core.system.pm.BPackageManagerService;
import com.hush.core.system.pm.BXposedManagerService;
import com.hush.core.system.user.BUserHandle;
import com.hush.core.system.user.BUserManagerService;
import com.hush.entity.pm.InstallOption;
import com.hush.utils.FileUtils;

public class VBoxSystem {

    private static volatile VBoxSystem sVBoxSystem;
    private final List<ISystemService> mServices = new ArrayList<>();
    private static final AtomicBoolean isStartup = new AtomicBoolean(false);

    private VBoxSystem() { }

    public static VBoxSystem getSystem() {
        if (sVBoxSystem == null) {
            synchronized (VBoxSystem.class) {
                if (sVBoxSystem == null) {
                    sVBoxSystem = new VBoxSystem();
                }
            }
        }
        return sVBoxSystem;
    }

    public void startup() {
        if (isStartup.getAndSet(true)) {
            return;
        }
        // Load virtual environment
        BEnvironment.load();
        // Register core system services
        mServices.add(BPackageManagerService.get());
        mServices.add(BUserManagerService.get());
        mServices.add(BActivityManagerService.get());
        mServices.add(BJobManagerService.get());
        mServices.add(BStorageManagerService.get());
        mServices.add(BPackageInstallerService.get());
        mServices.add(BXposedManagerService.get());
        mServices.add(BProcessManagerService.get());
        mServices.add(BAccountManagerService.get());
        mServices.add(BLocationManagerService.get());
        mServices.add(BNotificationManagerService.get());
        // Notify system ready
        for (ISystemService service : mServices) {
            try {
                service.systemReady();
            } catch (Throwable ignored) {
                // Never break virtual startup
            }
        }

        // Pre-install system apps
        List<String> preInstallPackages = AppSystemEnv.getPreInstallPackages();
        for (String pkg : preInstallPackages) {
            try {
                if (!BPackageManagerService.get().isInstalled(pkg, BUserHandle.USER_ALL)) {
                    PackageInfo info = HushCore.getPackageManager().getPackageInfo(pkg, 0);
                    BPackageManagerService.get().installPackageAsUser(info.applicationInfo.sourceDir,InstallOption.installBySystem(),BUserHandle.USER_ALL);
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            } catch (Throwable ignored) {
            }
        }
        // Init jar environment (SAFE)
        //initJarEnv();
    }
    
    private void initJarEnv() {
        // OPTIONAL: junit.jar (ignore if missing)
        try {
            InputStream junit = HushCore.getContext().getAssets().open("junit.jar");
            FileUtils.copyFile(junit, com.hush.activation.SdkBridge.JUNIT_JAR);
        } catch (Throwable ignored) {
            // junit.jar not present → safe to ignore
        }

        // REQUIRED: empty.jar
        try {
            InputStream empty = HushCore.getContext().getAssets().open("empty.jar");
            FileUtils.copyFile(empty, com.hush.activation.SdkBridge.EMPTY_JAR);
        } catch (Throwable e) {
            // empty.jar missing is a REAL problem
            e.printStackTrace();
        }
    }
}
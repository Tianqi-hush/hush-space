package com.hush.core.system;

import android.os.IBinder;

import java.util.HashMap;
import java.util.Map;

import com.hush.HushCore;
import com.hush.core.system.accounts.BAccountManagerService;
import com.hush.core.system.am.BActivityManagerService;
import com.hush.core.system.am.BJobManagerService;
import com.hush.core.system.location.BLocationManagerService;
import com.hush.core.system.notification.BNotificationManagerService;
import com.hush.core.system.os.BStorageManagerService;
import com.hush.core.system.pm.BPackageManagerService;
import com.hush.core.system.pm.BXposedManagerService;
import com.hush.core.system.user.BUserManagerService;

/**
 * Created by Milk on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ServiceManager {
    private static ServiceManager sServiceManager = null;
    public static final String ACTIVITY_MANAGER = "activity_manager";
    public static final String JOB_MANAGER = "job_manager";
    public static final String PACKAGE_MANAGER = "package_manager";
    public static final String STORAGE_MANAGER = "storage_manager";
    public static final String USER_MANAGER = "user_manager";
    public static final String XPOSED_MANAGER = "xposed_manager";
    public static final String ACCOUNT_MANAGER = "account_manager";
    public static final String LOCATION_MANAGER = "location_manager";
    public static final String NOTIFICATION_MANAGER = "notification_manager";

    private final Map<String, IBinder> mCaches = new HashMap<>();

    public static ServiceManager get() {
        if (sServiceManager == null) {
            synchronized (ServiceManager.class) {
                if (sServiceManager == null) {
                    sServiceManager = new ServiceManager();
                }
            }
        }
        return sServiceManager;
    }

    public static IBinder getService(String name) {
        return get().getServiceInternal(name);
    }

    private ServiceManager() {
        mCaches.put(ACTIVITY_MANAGER, BActivityManagerService.get());
        mCaches.put(JOB_MANAGER, BJobManagerService.get());
        mCaches.put(PACKAGE_MANAGER, BPackageManagerService.get());
        mCaches.put(STORAGE_MANAGER, BStorageManagerService.get());
        mCaches.put(USER_MANAGER, BUserManagerService.get());
        mCaches.put(XPOSED_MANAGER, BXposedManagerService.get());
        mCaches.put(ACCOUNT_MANAGER, BAccountManagerService.get());
        mCaches.put(LOCATION_MANAGER, BLocationManagerService.get());
        mCaches.put(NOTIFICATION_MANAGER, BNotificationManagerService.get());
    }

    public IBinder getServiceInternal(String name) {
        return mCaches.get(name);
    }

    public static void initBlackManager() {
        HushCore.get().getService(ACTIVITY_MANAGER);
        HushCore.get().getService(JOB_MANAGER);
        HushCore.get().getService(PACKAGE_MANAGER);
        HushCore.get().getService(STORAGE_MANAGER);
        HushCore.get().getService(USER_MANAGER);
        HushCore.get().getService(XPOSED_MANAGER);
        HushCore.get().getService(ACCOUNT_MANAGER);
        HushCore.get().getService(LOCATION_MANAGER);
        HushCore.get().getService(NOTIFICATION_MANAGER);
    }
}

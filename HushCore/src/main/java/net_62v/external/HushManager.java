package net_62v.external;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.IBinder;

import com.hush.HushCore;
import com.hush.app.configuration.AppLifecycleCallback;
import com.hush.app.configuration.ClientConfiguration;
import com.hush.core.system.user.BUserInfo;
import com.hush.entity.pm.InstallResult;
import com.hush.entity.pm.InstalledModule;

import java.io.File;
import java.util.List;

/**
 * HushManager — Public API wrapper for HushCore.
 * All HushCore functionality exposed as static methods.
 * Usage: HushManager.launchApk("com.example.app", 0);
 */
public class HushManager {

    // ─────────────────────────────────────────────────────────────────────────
    //  INIT
    // ─────────────────────────────────────────────────────────────────────────

    public static void attachBaseContext(Context context, ClientConfiguration config) {
        HushCore.get().doAttachBaseContext(context, config);
    }

    public static void create() {
        HushCore.get().doCreate();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CONTEXT & SYSTEM
    // ─────────────────────────────────────────────────────────────────────────

    public static Context getContext() {
        return HushCore.getContext();
    }

    public static String getHostPackageName() {
        return HushCore.getHostPkg();
    }

    public static int getHostUid() {
        return HushCore.getHostUid();
    }

    public static int getHostUserId() {
        return HushCore.getHostUserId();
    }

    public static android.content.pm.PackageManager getPackageManager() {
        return HushCore.getPackageManager();
    }

    public static boolean is64Bit() {
        return HushCore.is64Bit();
    }

    public static boolean checkSelfPermission(String permission) {
        return HushCore.get().checkSelfPermission(permission);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PROCESS TYPE
    // ─────────────────────────────────────────────────────────────────────────

    public static boolean isMainProcess() {
        return HushCore.get().isMainProcess();
    }

    public static boolean isServerProcess() {
        return HushCore.get().isServerProcess();
    }

    public static boolean isBlackProcess() {
        return HushCore.get().isBlackProcess();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SETTINGS
    // ─────────────────────────────────────────────────────────────────────────

    public static void setHideRoot(boolean hide) {
        HushCore.setHideRoot(hide);
    }

    public static void setHideXposed(boolean hide) {
        HushCore.setHideXposed(hide);
    }

    public static void setEnableDaemonService(boolean enable) {
        HushCore.setEnableDaemonService(enable);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ACTIVITY / LAUNCH
    // ─────────────────────────────────────────────────────────────────────────

    public static void startActivity(Intent intent, int userId) {
        HushCore.get().startActivity(intent, userId);
    }

    public static boolean launchApk(String packageName, int userId) {
        return HushCore.get().launchApk(packageName, userId);
    }

    public static void onBeforeMainLaunchApk(String packageName, int userId) {
        HushCore.get().onBeforeMainLaunchApk(packageName, userId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PACKAGE INSTALL / UNINSTALL
    // ─────────────────────────────────────────────────────────────────────────

    public static boolean isInstalled(String packageName, int userId) {
        return HushCore.get().isInstalled(packageName, userId);
    }

    public static InstallResult installPackage(String packageName, int userId) {
        return HushCore.get().installPackageAsUser(packageName, userId);
    }

    public static InstallResult installPackage(File apk, int userId) {
        return HushCore.get().installPackageAsUser(apk, userId);
    }

    public static InstallResult installPackage(Uri apk, int userId) {
        return HushCore.get().installPackageAsUser(apk, userId);
    }

    public static void uninstallPackage(String packageName) {
        HushCore.get().uninstallPackage(packageName);
    }

    public static void uninstallPackageAsUser(String packageName, int userId) {
        HushCore.get().uninstallPackageAsUser(packageName, userId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  APP INFO
    // ─────────────────────────────────────────────────────────────────────────

    public static ApplicationInfo getApplicationInfo(String packageName) {
        return HushCore.get().getApplicationInfo(packageName);
    }

    public static List<ApplicationInfo> getInstalledApplications(int flags, int userId) {
        return HushCore.get().getInstalledApplications(flags, userId);
    }

    public static List<PackageInfo> getInstalledPackages(int flags, int userId) {
        return HushCore.get().getInstalledPackages(flags, userId);
    }

    public static boolean isAppRunning(String packageName, int userId) {
        return HushCore.get().isAppRunning(packageName, userId);
    }

    public static void clearPackage(String packageName, int userId) {
        HushCore.get().clearPackage(packageName, userId);
    }

    public static void stopPackage(String packageName, int userId) {
        HushCore.get().stopPackage(packageName, userId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  XPOSED / MODULE
    // ─────────────────────────────────────────────────────────────────────────

    public static InstallResult installXPModule(File apk) {
        return HushCore.get().installXPModule(apk);
    }

    public static InstallResult installXPModule(Uri apk) {
        return HushCore.get().installXPModule(apk);
    }

    public static InstallResult installXPModule(String packageName) {
        return HushCore.get().installXPModule(packageName);
    }

    public static void uninstallXPModule(String packageName) {
        HushCore.get().uninstallXPModule(packageName);
    }

    public static boolean isXPEnable() {
        return HushCore.get().isXPEnable();
    }

    public static void setXPEnable(boolean enable) {
        HushCore.get().setXPEnable(enable);
    }

    public static boolean isXposedModule(File file) {
        return HushCore.get().isXposedModule(file);
    }

    public static boolean isInstalledXposedModule(String packageName) {
        return HushCore.get().isInstalledXposedModule(packageName);
    }

    public static boolean isModuleEnable(String packageName) {
        return HushCore.get().isModuleEnable(packageName);
    }

    public static void setModuleEnable(String packageName, boolean enable) {
        HushCore.get().setModuleEnable(packageName, enable);
    }

    public static List<InstalledModule> getInstalledXPModules() {
        return HushCore.get().getInstalledXPModules();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  USER MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────────

    public static List<BUserInfo> getUsers() {
        return HushCore.get().getUsers();
    }

    public static BUserInfo createUser(int userId) {
        return HushCore.get().createUser(userId);
    }

    public static void deleteUser(int userId) {
        HushCore.get().deleteUser(userId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LIFECYCLE CALLBACKS
    // ─────────────────────────────────────────────────────────────────────────

    public static void addAppLifecycleCallback(AppLifecycleCallback callback) {
        HushCore.get().addAppLifecycleCallback(callback);
    }

    public static void removeAppLifecycleCallback(AppLifecycleCallback callback) {
        HushCore.get().removeAppLifecycleCallback(callback);
    }

    public static List<AppLifecycleCallback> getAppLifecycleCallbacks() {
        return HushCore.get().getAppLifecycleCallbacks();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GMS
    // ─────────────────────────────────────────────────────────────────────────

    public static boolean isSupportGms() {
        return HushCore.get().isSupportGms();
    }

    public static boolean isInstallGms(int userId) {
        return HushCore.get().isInstallGms(userId);
    }

    public static InstallResult installGms(int userId) {
        return HushCore.get().installGms(userId);
    }

    public static boolean uninstallGms(int userId) {
        return HushCore.get().uninstallGms(userId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SERVICE
    // ─────────────────────────────────────────────────────────────────────────

    public static IBinder getService(String name) {
        return HushCore.get().getService(name);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  EXCEPTION HANDLER
    // ─────────────────────────────────────────────────────────────────────────

    public static void setExceptionHandler(Thread.UncaughtExceptionHandler handler) {
        HushCore.get().setExceptionHandler(handler);
    }

    public static Thread.UncaughtExceptionHandler getExceptionHandler() {
        return HushCore.get().getExceptionHandler();
    }
}
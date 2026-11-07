package com.hush.core;

import android.content.pm.PackageManager;
import java.util.HashSet;
import java.util.Set;
import com.hush.HushCore;
import com.hush.entity.pm.InstallResult;
import com.hush.utils.auth.Auth;
import org.lsposed.lsparanoid.Obfuscate;

@Obfuscate
public class GmsCore {
    private static final HashSet<String> GOOGLE_APP = new HashSet<>();
    private static final HashSet<String> GOOGLE_SERVICE = new HashSet<>();
    public static final String GMS_PKG = "com.google.android.gms";
    public static final String GSF_PKG = "com.google.android.gsf";
    public static final String VENDING_PKG = "com.android.vending";

    static {
        GOOGLE_APP.add(VENDING_PKG);
        GOOGLE_APP.add("com.google.android.play.games");
        GOOGLE_APP.add("com.google.android.wearable.app");
        GOOGLE_APP.add("com.google.android.wearable.app.cn");

        // GMS must install at first
        GOOGLE_SERVICE.add(GMS_PKG);
        GOOGLE_SERVICE.add(GSF_PKG);
        GOOGLE_SERVICE.add("com.google.android.gsf.login");
        GOOGLE_SERVICE.add("com.google.android.backuptransport");
        GOOGLE_SERVICE.add("com.google.android.backup");
        GOOGLE_SERVICE.add("com.google.android.configupdater");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.contacts");
        GOOGLE_SERVICE.add("com.google.android.feedback");
        GOOGLE_SERVICE.add("com.google.android.onetimeinitializer");
        GOOGLE_SERVICE.add("com.google.android.partnersetup");
        GOOGLE_SERVICE.add("com.google.android.setupwizard");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.calendar");
    }

    // Known GMS content-provider authority prefixes that must be routed directly
    // to the real system (not through the virtual provider path).
    // The check covers authority strings like "com.google.android.gsf.gservices",
    // "com.google.android.gms.phenotype", "com.google.settings", etc.
    private static final String[] GOOGLE_AUTHORITY_PREFIXES = {
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.backuptransport",
        "com.google.android.configupdater",
        "com.google.android.syncadapters",
        "com.google.android.feedback",
        "com.google.android.onetimeinitializer",
        "com.google.android.partnersetup",
        "com.google.android.setupwizard",
        "com.google.settings",
        "com.android.vending",
    };

    // Old GMS bypass methods (isGoogleAppOrService, setGoogleAppOrService) 
    // have been removed. We now want GMS to route completely through the 
    // virtual environment (BAccountManager, etc).

    private static InstallResult installPackages(Set<String> list, int userId) {
        HushCore sHushCore = HushCore.get();
        for (String packageName : list) {
            if (sHushCore.isInstalled(packageName, userId)) {
                continue;
            }

            try {
                HushCore.getContext().getPackageManager().getApplicationInfo(packageName, 0);
            } catch (PackageManager.NameNotFoundException ignored) {
                continue;
            }

            InstallResult installResult = sHushCore.installPackageAsUser(packageName, userId);
            if (!installResult.success) {
                return installResult;
            }
        }
        return new InstallResult();
    }

    private static void uninstallPackages(Set<String> list, int userId) {
        HushCore sHushCore = HushCore.get();
        for (String packageName : list) {
            sHushCore.uninstallPackageAsUser(packageName, userId);
        }
    }

    public static InstallResult installGApps(int userId) {
        Set<String> googleApps = new HashSet<>();

        googleApps.addAll(GOOGLE_SERVICE);
        googleApps.addAll(GOOGLE_APP);

        InstallResult installResult = installPackages(googleApps, userId);
        if (!installResult.success) {
            uninstallGApps(userId);
            return installResult;
        }
        // Pre-seed the Chimera module config file so GMS doesn't loop on
        // ENOENT when it tries to read current_config.fb before writing it.
        // Without this, ChimeraCfgMgr logs 6+ failures per process start and
        // the read-after-write still fails due to IO-rule path mismatch.
        try {
            android.content.Context ctx = HushCore.getContext();
            if (ctx != null) {
                String base = ctx.getFilesDir().getAbsolutePath();
                // GMS uses both user and user_de paths
                String[] chimeraDirs = {
                    base + "/data/user/" + userId + "/" + GMS_PKG + "/app_chimera",
                    base + "/data/user_de/" + userId + "/" + GMS_PKG + "/app_chimera",
                };
                for (String dir : chimeraDirs) {
                    java.io.File d = new java.io.File(dir);
                    if (!d.exists()) d.mkdirs();
                    java.io.File cfg = new java.io.File(d, "current_config.fb");
                    if (!cfg.exists()) {
                        // Write a zero-length file — ChimeraCfgMgr treats
                        // a missing/empty config as blank and fills defaults.
                        cfg.createNewFile();
                        cfg.setReadable(true, false);
                        cfg.setWritable(true, false);
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("GmsCore", "Failed to pre-seed chimera config: " + t.getMessage());
        }
        return installResult;
    }

    public static void uninstallGApps(int userId) {
        uninstallPackages(GOOGLE_SERVICE, userId);
        uninstallPackages(GOOGLE_APP, userId);
    }

    public static void remove(String packageName) {
        GOOGLE_SERVICE.remove(packageName);
        GOOGLE_APP.remove(packageName);
    }

    public static boolean isSupportGms() {
        try {
            HushCore.getPackageManager().getPackageInfo(GMS_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) { }
        return false;
    }

    public static boolean isInstalledGoogleService(int userId) {
        return HushCore.get().isInstalled(GMS_PKG, userId);
    }
}
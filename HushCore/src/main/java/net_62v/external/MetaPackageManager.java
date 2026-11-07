package net_62v.external;

import android.content.pm.ApplicationInfo;
import org.lsposed.lsparanoid.Obfuscate;

@Obfuscate
public class MetaPackageManager {

    /**
     * Checks if the application is currently installed inside the virtual environment.
     * Called by StartActivity and ApkEnv to verify installation status before cloning.
     */
    public static boolean isInnerAppInstalled(String packageName) {
        // Routes the check to user 0 in your Zcore engine
        return HushManager.isInstalled(packageName, 0);
    }

    /**
     * Retrieves the ApplicationInfo for a package installed in the virtual environment.
     * Called by ApkEnv to fetch package details.
     */
    public static ApplicationInfo getApplicationInfo(String packageName) {
        // Fetches directly from your engine's package manager
        return HushManager.getApplicationInfo(packageName);
    }
}

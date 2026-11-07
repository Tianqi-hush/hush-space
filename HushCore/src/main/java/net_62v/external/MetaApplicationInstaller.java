package net_62v.external;

import java.io.File;
import org.lsposed.lsparanoid.Obfuscate;

@Obfuscate
public class MetaApplicationInstaller {

    /**
     * Clones an application that is already installed on the host device into the virtual environment.
     * Called by StartActivity to clone the 64-bit application.
     */
    public static void cloneApp(String packageName) {
        HushManager.installPackage(packageName, 0);
    }

    /**
     * Installs an application into the virtual environment from a specific APK file path.
     * Called by ApkEnv to handle direct file installations.
     */
    public static void installAppByPath(String apkPath) {
        HushManager.installPackage(new File(apkPath), 0);
    }
}

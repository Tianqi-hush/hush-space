package com.hush.core.system.pm.installer;

import com.hush.core.env.BEnvironment;
import com.hush.core.system.pm.BPackageSettings;
import com.hush.entity.pm.InstallOption;
import com.hush.utils.FileUtils;
import java.io.File;

public class CreateUserExecutor implements Executor {
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        String packageName = ps.pkg.packageName;
        FileUtils.mkdirs(BEnvironment.getDataDir(packageName, userId));
        FileUtils.mkdirs(BEnvironment.getDeDataDir(packageName, userId));
        FileUtils.mkdirs(new File(BEnvironment.getDeDataDir(packageName, userId), "databases"));
        FileUtils.mkdirs(new File(BEnvironment.getDeDataDir(packageName, userId), "files"));
        FileUtils.mkdirs(new File(BEnvironment.getDeDataDir(packageName, userId), "cache"));
        FileUtils.mkdirs(new File(BEnvironment.getDeDataDir(packageName, userId), "shared_prefs"));
        FileUtils.mkdirs(new File(BEnvironment.getDeDataDir(packageName, userId), "no_backup"));
        
        FileUtils.mkdirs(BEnvironment.getDataCacheDir(packageName, userId));
        FileUtils.mkdirs(BEnvironment.getDataDatabasesDir(packageName, userId));
        FileUtils.mkdirs(BEnvironment.getDataFilesDir(packageName, userId));
        FileUtils.mkdirs(BEnvironment.getExternalDataCacheDir(packageName));
        FileUtils.mkdirs(BEnvironment.getExternalDataFilesDir(packageName));
        return 0;
    }
}

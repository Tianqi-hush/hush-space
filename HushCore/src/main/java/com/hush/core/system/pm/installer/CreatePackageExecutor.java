package com.hush.core.system.pm.installer;

import com.hush.core.env.BEnvironment;
import com.hush.core.system.pm.BPackageSettings;
import com.hush.entity.pm.InstallOption;
import com.hush.utils.FileUtils;


public class CreatePackageExecutor implements Executor {
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        FileUtils.deleteDir(BEnvironment.getAppDir(ps.pkg.packageName));
        FileUtils.mkdirs(BEnvironment.getAppDir(ps.pkg.packageName));
        FileUtils.mkdirs(BEnvironment.getAppLibDir(ps.pkg.packageName));
        return 0;
    }
}

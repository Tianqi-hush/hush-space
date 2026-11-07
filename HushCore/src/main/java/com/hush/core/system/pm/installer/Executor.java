package com.hush.core.system.pm.installer;

import com.hush.core.system.pm.BPackageSettings;
import com.hush.entity.pm.InstallOption;

public interface Executor {
    String TAG = "InstallExecutor";

    int exec(BPackageSettings bPackageSettings, InstallOption installOption, int i);
}

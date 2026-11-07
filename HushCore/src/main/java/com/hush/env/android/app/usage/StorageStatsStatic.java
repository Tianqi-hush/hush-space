package com.hush.env.android.app.usage;

import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BConstructorNotProcess;

@BClassNameNotProcess("android.app.usage.StorageStats")
public interface StorageStatsStatic {
  @BConstructorNotProcess
  StorageStats _new();
}

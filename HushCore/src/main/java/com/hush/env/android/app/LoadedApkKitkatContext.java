package com.hush.env.android.app;

import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.LoadedApk")
public interface LoadedApkKitkatContext {
  @BFieldSetNotProcess
  void _set_mDisplayAdjustments(final Object value);

  @BFieldCheckNotProcess
  Field _check_mDisplayAdjustments();

  @BFieldNotProcess
  Object mDisplayAdjustments();
}

package com.hush.env.android.app;

import java.io.File;
import java.lang.Object;
import java.lang.String;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.ContextImpl")
public interface ContextImplKitkatContext {
  @BFieldSetNotProcess
  void _set_mDisplayAdjustments(final Object value);

  @BFieldCheckNotProcess
  Field _check_mDisplayAdjustments();

  @BFieldNotProcess
  Object mDisplayAdjustments();

  @BFieldSetNotProcess
  void _set_mExternalCacheDirs(final Object value);

  @BFieldCheckNotProcess
  Field _check_mExternalCacheDirs();

  @BFieldNotProcess
  File[] mExternalCacheDirs();

  @BFieldSetNotProcess
  void _set_mExternalFilesDirs(final Object value);

  @BFieldCheckNotProcess
  Field _check_mExternalFilesDirs();

  @BFieldNotProcess
  File[] mExternalFilesDirs();

  @BFieldSetNotProcess
  void _set_mOpPackageName(final Object value);

  @BFieldCheckNotProcess
  Field _check_mOpPackageName();

  @BFieldNotProcess
  String mOpPackageName();
}

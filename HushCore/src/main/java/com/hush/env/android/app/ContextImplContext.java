package com.hush.env.android.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import java.lang.Object;
import java.lang.String;
import java.lang.Void;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.app.ContextImpl")
public interface ContextImplContext {
  @BMethodCheckNotProcess
  Method _check_getReceiverRestrictedContext();

  Context getReceiverRestrictedContext();

  @BMethodCheckNotProcess
  Method _check_setOuterContext(Context Context0);

  Void setOuterContext(Context Context0);

  @BMethodCheckNotProcess
  Method _check_getAttributionSource();

  Object getAttributionSource();

  @BMethodCheckNotProcess
  Method _check_getPackageManager();

  PackageManager getPackageManager();

  @BFieldSetNotProcess
  void _set_mBasePackageName(final Object value);

  @BFieldCheckNotProcess
  Field _check_mBasePackageName();

  @BFieldNotProcess
  String mBasePackageName();

  @BFieldSetNotProcess
  void _set_mContentResolver(final Object value);

  @BFieldCheckNotProcess
  Field _check_mContentResolver();

  @BFieldNotProcess
  ContentResolver mContentResolver();

  @BFieldSetNotProcess
  void _set_mPackageInfo(final Object value);

  @BFieldCheckNotProcess
  Field _check_mPackageInfo();

  @BFieldNotProcess
  Object mPackageInfo();

  @BFieldSetNotProcess
  void _set_mPackageManager(final Object value);

  @BFieldCheckNotProcess
  Field _check_mPackageManager();

  @BFieldNotProcess
  PackageManager mPackageManager();
}

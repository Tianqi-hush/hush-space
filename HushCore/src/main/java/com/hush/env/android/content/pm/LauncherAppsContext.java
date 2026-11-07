package com.hush.env.android.content.pm;

import android.content.pm.PackageManager;
import android.os.IInterface;
import android.os.UserManager;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.content.pm.LauncherApps")
public interface LauncherAppsContext {
  @BFieldSetNotProcess
  void _set_mPm(final Object value);

  @BFieldCheckNotProcess
  Field _check_mPm();

  @BFieldNotProcess
  PackageManager mPm();

  @BFieldSetNotProcess
  void _set_mService(final Object value);

  @BFieldCheckNotProcess
  Field _check_mService();

  @BFieldNotProcess
  IInterface mService();

  @BFieldSetNotProcess
  void _set_mUserManager(final Object value);

  @BFieldCheckNotProcess
  Field _check_mUserManager();

  @BFieldNotProcess
  UserManager mUserManager();
}

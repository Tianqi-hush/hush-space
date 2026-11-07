package com.hush.env.android.app;

import android.os.IInterface;
import java.lang.Integer;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.AlarmManager")
public interface AlarmManagerContext {
  @BFieldSetNotProcess
  void _set_mService(final Object value);

  @BFieldCheckNotProcess
  Field _check_mService();

  @BFieldNotProcess
  IInterface mService();

  @BFieldSetNotProcess
  void _set_mTargetSdkVersion(final Object value);

  @BFieldCheckNotProcess
  Field _check_mTargetSdkVersion();

  @BFieldNotProcess
  Integer mTargetSdkVersion();
}

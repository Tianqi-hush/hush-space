package com.hush.env.android.app;

import android.content.Context;
import android.content.ServiceConnection;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.LoadedApk$ServiceDispatcher")
public interface LoadedApkServiceDispatcherContext {
  @BFieldSetNotProcess
  void _set_mConnection(final Object value);

  @BFieldCheckNotProcess
  Field _check_mConnection();

  @BFieldNotProcess
  ServiceConnection mConnection();

  @BFieldSetNotProcess
  void _set_mContext(final Object value);

  @BFieldCheckNotProcess
  Field _check_mContext();

  @BFieldNotProcess
  Context mContext();
}

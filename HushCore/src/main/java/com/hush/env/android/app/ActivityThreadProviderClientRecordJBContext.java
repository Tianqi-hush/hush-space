package com.hush.env.android.app;

import android.os.IInterface;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.ActivityThread$ProviderClientRecord")
public interface ActivityThreadProviderClientRecordJBContext {
  @BFieldSetNotProcess
  void _set_mHolder(final Object value);

  @BFieldCheckNotProcess
  Field _check_mHolder();

  @BFieldNotProcess
  Object mHolder();

  @BFieldSetNotProcess
  void _set_mProvider(final Object value);

  @BFieldCheckNotProcess
  Field _check_mProvider();

  @BFieldNotProcess
  IInterface mProvider();
}

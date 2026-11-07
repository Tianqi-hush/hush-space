package com.hush.env.android.app;

import android.os.IInterface;
import java.lang.Object;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.app.NotificationManager")
public interface NotificationManagerStatic {
  @BMethodCheckNotProcess
  Method _check_getService();

  IInterface getService();

  @BFieldSetNotProcess
  void _set_sService(final Object value);

  @BFieldCheckNotProcess
  Field _check_sService();

  @BFieldNotProcess
  IInterface sService();
}

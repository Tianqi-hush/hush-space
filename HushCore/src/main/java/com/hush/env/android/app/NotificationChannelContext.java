package com.hush.env.android.app;

import java.lang.Object;
import java.lang.String;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.NotificationChannel")
public interface NotificationChannelContext {
  @BFieldSetNotProcess
  void _set_mId(final Object value);

  @BFieldCheckNotProcess
  Field _check_mId();

  @BFieldNotProcess
  String mId();
}

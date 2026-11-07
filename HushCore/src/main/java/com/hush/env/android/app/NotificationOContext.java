package com.hush.env.android.app;

import java.lang.Object;
import java.lang.String;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.Notification")
public interface NotificationOContext {
  @BFieldSetNotProcess
  void _set_mChannelId(final Object value);

  @BFieldCheckNotProcess
  Field _check_mChannelId();

  @BFieldNotProcess
  String mChannelId();

  @BFieldSetNotProcess
  void _set_mGroupKey(final Object value);

  @BFieldCheckNotProcess
  Field _check_mGroupKey();

  @BFieldNotProcess
  String mGroupKey();
}

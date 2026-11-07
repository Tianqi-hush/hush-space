package com.hush.env.android.app;

import android.graphics.drawable.Icon;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.Notification")
public interface NotificationMContext {
  @BFieldSetNotProcess
  void _set_mLargeIcon(final Object value);

  @BFieldCheckNotProcess
  Field _check_mLargeIcon();

  @BFieldNotProcess
  Icon mLargeIcon();

  @BFieldSetNotProcess
  void _set_mSmallIcon(final Object value);

  @BFieldCheckNotProcess
  Field _check_mSmallIcon();

  @BFieldNotProcess
  Icon mSmallIcon();
}

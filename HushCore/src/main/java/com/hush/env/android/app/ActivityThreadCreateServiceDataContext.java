package com.hush.env.android.app;

import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.ActivityThread$CreateServiceData")
public interface ActivityThreadCreateServiceDataContext {
  @BFieldSetNotProcess
  void _set_compatInfo(final Object value);

  @BFieldCheckNotProcess
  Field _check_compatInfo();

  @BFieldNotProcess
  Object compatInfo();

  @BFieldSetNotProcess
  void _set_info(final Object value);

  @BFieldCheckNotProcess
  Field _check_info();

  @BFieldNotProcess
  ServiceInfo info();

  @BFieldSetNotProcess
  void _set_intent(final Object value);

  @BFieldCheckNotProcess
  Field _check_intent();

  @BFieldNotProcess
  Intent intent();

  @BFieldSetNotProcess
  void _set_token(final Object value);

  @BFieldCheckNotProcess
  Field _check_token();

  @BFieldNotProcess
  IBinder token();
}

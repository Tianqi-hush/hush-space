package com.hush.env.android.app;

import java.lang.Integer;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.ActivityThread$H")
public interface ActivityThreadHStatic {
  @BFieldSetNotProcess
  void _set_CREATE_SERVICE(final Object value);

  @BFieldCheckNotProcess
  Field _check_CREATE_SERVICE();

  @BFieldNotProcess
  Integer CREATE_SERVICE();

  @BFieldSetNotProcess
  void _set_EXECUTE_TRANSACTION(final Object value);

  @BFieldCheckNotProcess
  Field _check_EXECUTE_TRANSACTION();

  @BFieldNotProcess
  Integer EXECUTE_TRANSACTION();

  @BFieldSetNotProcess
  void _set_LAUNCH_ACTIVITY(final Object value);

  @BFieldCheckNotProcess
  Field _check_LAUNCH_ACTIVITY();

  @BFieldNotProcess
  Integer LAUNCH_ACTIVITY();

  @BFieldSetNotProcess
  void _set_SCHEDULE_CRASH(final Object value);

  @BFieldCheckNotProcess
  Field _check_SCHEDULE_CRASH();

  @BFieldNotProcess
  Integer SCHEDULE_CRASH();
}

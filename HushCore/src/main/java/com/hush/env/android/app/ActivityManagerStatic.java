package com.hush.env.android.app;

import java.lang.Integer;
import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.ActivityManager")
public interface ActivityManagerStatic {
  @BFieldSetNotProcess
  void _set_START_INTENT_NOT_RESOLVED(final Object value);

  @BFieldCheckNotProcess
  Field _check_START_INTENT_NOT_RESOLVED();

  @BFieldNotProcess
  Integer START_INTENT_NOT_RESOLVED();

  @BFieldSetNotProcess
  void _set_START_NOT_CURRENT_USER_ACTIVITY(final Object value);

  @BFieldCheckNotProcess
  Field _check_START_NOT_CURRENT_USER_ACTIVITY();

  @BFieldNotProcess
  Integer START_NOT_CURRENT_USER_ACTIVITY();

  @BFieldSetNotProcess
  void _set_START_SUCCESS(final Object value);

  @BFieldCheckNotProcess
  Field _check_START_SUCCESS();

  @BFieldNotProcess
  Integer START_SUCCESS();

  @BFieldSetNotProcess
  void _set_START_TASK_TO_FRONT(final Object value);

  @BFieldCheckNotProcess
  Field _check_START_TASK_TO_FRONT();

  @BFieldNotProcess
  Integer START_TASK_TO_FRONT();
}

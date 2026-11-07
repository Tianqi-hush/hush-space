package com.hush.env.android.app;

import java.lang.Object;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.app.ActivityTaskManager")
public interface ActivityTaskManagerStatic {
  @BMethodCheckNotProcess
  Method _check_getService();

  Object getService();

  @BFieldSetNotProcess
  void _set_IActivityTaskManagerSingleton(final Object value);

  @BFieldCheckNotProcess
  Field _check_IActivityTaskManagerSingleton();

  @BFieldNotProcess
  Object IActivityTaskManagerSingleton();
}

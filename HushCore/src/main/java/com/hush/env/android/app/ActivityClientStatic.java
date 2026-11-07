package com.hush.env.android.app;

import java.lang.Object;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.app.ActivityClient")
public interface ActivityClientStatic {
  @BMethodCheckNotProcess
  Method _check_getInstance();

  Object getInstance();

  @BMethodCheckNotProcess
  Method _check_getActivityClientController();

  Object getActivityClientController();

  @BMethodCheckNotProcess
  Method _check_setActivityClientController(Object iInterface);

  Object setActivityClientController(Object iInterface);
}

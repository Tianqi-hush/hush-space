package com.hush.env.android.app;

import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("android.app.ActivityClient")
public interface ActivityClientContext {
  @BFieldSetNotProcess
  void _set_INTERFACE_SINGLETON(final Object value);

  @BFieldCheckNotProcess
  Field _check_INTERFACE_SINGLETON();

  @BFieldNotProcess
  Object INTERFACE_SINGLETON();
}

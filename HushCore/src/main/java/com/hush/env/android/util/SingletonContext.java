package com.hush.env.android.util;

import java.lang.Object;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;
import com.hush.reflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("android.util.Singleton")
public interface SingletonContext {
  @BMethodCheckNotProcess
  Method _check_get();

  Object get();

  @BFieldSetNotProcess
  void _set_mInstance(final Object value);

  @BFieldCheckNotProcess
  Field _check_mInstance();

  @BFieldNotProcess
  Object mInstance();
}

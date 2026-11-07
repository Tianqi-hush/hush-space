package com.hush.env.dalvik.system;

import java.lang.Object;
import java.lang.reflect.Field;
import com.hush.reflection.annotation.BClassNameNotProcess;
import com.hush.reflection.annotation.BFieldCheckNotProcess;
import com.hush.reflection.annotation.BFieldNotProcess;
import com.hush.reflection.annotation.BFieldSetNotProcess;

@BClassNameNotProcess("dalvik.system.BaseDexClassLoader")
public interface BaseDexClassLoaderContext {
  @BFieldSetNotProcess
  void _set_sharedLibraryLoaders(final Object value);

  @BFieldCheckNotProcess
  Field _check_sharedLibraryLoaders();

  @BFieldNotProcess
  Object sharedLibraryLoaders();
}

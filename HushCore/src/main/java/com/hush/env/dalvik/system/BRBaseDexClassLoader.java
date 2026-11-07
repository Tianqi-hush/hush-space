package com.hush.env.dalvik.system;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRBaseDexClassLoader {
  public static BaseDexClassLoaderStatic getWithException() {
    return BlackReflection.create(BaseDexClassLoaderStatic.class, null, true);
  }

  public static BaseDexClassLoaderStatic get() {
    return BlackReflection.create(BaseDexClassLoaderStatic.class, null, false);
  }

  public static BaseDexClassLoaderContext getWithException(final Object caller) {
    return BlackReflection.create(BaseDexClassLoaderContext.class, caller, true);
  }

  public static BaseDexClassLoaderContext get(final Object caller) {
    return BlackReflection.create(BaseDexClassLoaderContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(BaseDexClassLoaderContext.class);
  }
}

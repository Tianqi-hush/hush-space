package com.hush.env.android.os;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BREnvironment {
  public static EnvironmentStatic getWithException() {
    return BlackReflection.create(EnvironmentStatic.class, null, true);
  }

  public static EnvironmentStatic get() {
    return BlackReflection.create(EnvironmentStatic.class, null, false);
  }

  public static EnvironmentContext getWithException(final Object caller) {
    return BlackReflection.create(EnvironmentContext.class, caller, true);
  }

  public static EnvironmentContext get(final Object caller) {
    return BlackReflection.create(EnvironmentContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(EnvironmentContext.class);
  }
}

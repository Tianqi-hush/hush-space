package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRILocaleManager {
  public static ILocaleManagerStatic getWithException() {
    return BlackReflection.create(ILocaleManagerStatic.class, null, true);
  }

  public static ILocaleManagerStatic get() {
    return BlackReflection.create(ILocaleManagerStatic.class, null, false);
  }

  public static ILocaleManagerContext getWithException(final Object caller) {
    return BlackReflection.create(ILocaleManagerContext.class, caller, true);
  }

  public static ILocaleManagerContext get(final Object caller) {
    return BlackReflection.create(ILocaleManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ILocaleManagerContext.class);
  }
}

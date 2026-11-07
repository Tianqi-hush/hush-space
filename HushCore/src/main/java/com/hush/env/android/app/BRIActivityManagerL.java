package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIActivityManagerL {
  public static IActivityManagerLStatic getWithException() {
    return BlackReflection.create(IActivityManagerLStatic.class, null, true);
  }

  public static IActivityManagerLStatic get() {
    return BlackReflection.create(IActivityManagerLStatic.class, null, false);
  }

  public static IActivityManagerLContext getWithException(final Object caller) {
    return BlackReflection.create(IActivityManagerLContext.class, caller, true);
  }

  public static IActivityManagerLContext get(final Object caller) {
    return BlackReflection.create(IActivityManagerLContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IActivityManagerLContext.class);
  }
}

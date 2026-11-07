package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRActivityThreadH {
  public static ActivityThreadHStatic getWithException() {
    return BlackReflection.create(ActivityThreadHStatic.class, null, true);
  }

  public static ActivityThreadHStatic get() {
    return BlackReflection.create(ActivityThreadHStatic.class, null, false);
  }

  public static ActivityThreadHContext getWithException(final Object caller) {
    return BlackReflection.create(ActivityThreadHContext.class, caller, true);
  }

  public static ActivityThreadHContext get(final Object caller) {
    return BlackReflection.create(ActivityThreadHContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ActivityThreadHContext.class);
  }
}

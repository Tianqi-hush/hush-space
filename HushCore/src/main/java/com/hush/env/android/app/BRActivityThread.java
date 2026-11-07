package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRActivityThread {
  public static ActivityThreadStatic getWithException() {
    return BlackReflection.create(ActivityThreadStatic.class, null, true);
  }

  public static ActivityThreadStatic get() {
    return BlackReflection.create(ActivityThreadStatic.class, null, false);
  }

  public static ActivityThreadContext getWithException(final Object caller) {
    return BlackReflection.create(ActivityThreadContext.class, caller, true);
  }

  public static ActivityThreadContext get(final Object caller) {
    return BlackReflection.create(ActivityThreadContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ActivityThreadContext.class);
  }
}

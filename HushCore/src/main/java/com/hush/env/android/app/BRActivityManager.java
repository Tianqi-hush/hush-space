package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRActivityManager {
  public static ActivityManagerStatic getWithException() {
    return BlackReflection.create(ActivityManagerStatic.class, null, true);
  }

  public static ActivityManagerStatic get() {
    return BlackReflection.create(ActivityManagerStatic.class, null, false);
  }

  public static ActivityManagerContext getWithException(final Object caller) {
    return BlackReflection.create(ActivityManagerContext.class, caller, true);
  }

  public static ActivityManagerContext get(final Object caller) {
    return BlackReflection.create(ActivityManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ActivityManagerContext.class);
  }
}

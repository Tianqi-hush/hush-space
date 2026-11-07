package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRActivityManagerNative {
  public static ActivityManagerNativeStatic getWithException() {
    return BlackReflection.create(ActivityManagerNativeStatic.class, null, true);
  }

  public static ActivityManagerNativeStatic get() {
    return BlackReflection.create(ActivityManagerNativeStatic.class, null, false);
  }

  public static ActivityManagerNativeContext getWithException(final Object caller) {
    return BlackReflection.create(ActivityManagerNativeContext.class, caller, true);
  }

  public static ActivityManagerNativeContext get(final Object caller) {
    return BlackReflection.create(ActivityManagerNativeContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ActivityManagerNativeContext.class);
  }
}

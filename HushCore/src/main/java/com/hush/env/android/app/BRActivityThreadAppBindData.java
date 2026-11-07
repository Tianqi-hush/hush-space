package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRActivityThreadAppBindData {
  public static ActivityThreadAppBindDataStatic getWithException() {
    return BlackReflection.create(ActivityThreadAppBindDataStatic.class, null, true);
  }

  public static ActivityThreadAppBindDataStatic get() {
    return BlackReflection.create(ActivityThreadAppBindDataStatic.class, null, false);
  }

  public static ActivityThreadAppBindDataContext getWithException(final Object caller) {
    return BlackReflection.create(ActivityThreadAppBindDataContext.class, caller, true);
  }

  public static ActivityThreadAppBindDataContext get(final Object caller) {
    return BlackReflection.create(ActivityThreadAppBindDataContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ActivityThreadAppBindDataContext.class);
  }
}

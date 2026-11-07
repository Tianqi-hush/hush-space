package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRActivity {
  public static ActivityStatic getWithException() {
    return BlackReflection.create(ActivityStatic.class, null, true);
  }

  public static ActivityStatic get() {
    return BlackReflection.create(ActivityStatic.class, null, false);
  }

  public static ActivityContext getWithException(final Object caller) {
    return BlackReflection.create(ActivityContext.class, caller, true);
  }

  public static ActivityContext get(final Object caller) {
    return BlackReflection.create(ActivityContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ActivityContext.class);
  }
}

package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIActivityTaskManager {
  public static IActivityTaskManagerStatic getWithException() {
    return BlackReflection.create(IActivityTaskManagerStatic.class, null, true);
  }

  public static IActivityTaskManagerStatic get() {
    return BlackReflection.create(IActivityTaskManagerStatic.class, null, false);
  }

  public static IActivityTaskManagerContext getWithException(final Object caller) {
    return BlackReflection.create(IActivityTaskManagerContext.class, caller, true);
  }

  public static IActivityTaskManagerContext get(final Object caller) {
    return BlackReflection.create(IActivityTaskManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IActivityTaskManagerContext.class);
  }
}

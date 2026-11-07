package com.hush.env.android.content;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRPeriodicSync {
  public static PeriodicSyncStatic getWithException() {
    return BlackReflection.create(PeriodicSyncStatic.class, null, true);
  }

  public static PeriodicSyncStatic get() {
    return BlackReflection.create(PeriodicSyncStatic.class, null, false);
  }

  public static PeriodicSyncContext getWithException(final Object caller) {
    return BlackReflection.create(PeriodicSyncContext.class, caller, true);
  }

  public static PeriodicSyncContext get(final Object caller) {
    return BlackReflection.create(PeriodicSyncContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(PeriodicSyncContext.class);
  }
}

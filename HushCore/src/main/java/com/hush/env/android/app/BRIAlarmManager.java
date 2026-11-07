package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIAlarmManager {
  public static IAlarmManagerStatic getWithException() {
    return BlackReflection.create(IAlarmManagerStatic.class, null, true);
  }

  public static IAlarmManagerStatic get() {
    return BlackReflection.create(IAlarmManagerStatic.class, null, false);
  }

  public static IAlarmManagerContext getWithException(final Object caller) {
    return BlackReflection.create(IAlarmManagerContext.class, caller, true);
  }

  public static IAlarmManagerContext get(final Object caller) {
    return BlackReflection.create(IAlarmManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IAlarmManagerContext.class);
  }
}

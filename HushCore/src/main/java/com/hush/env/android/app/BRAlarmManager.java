package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRAlarmManager {
  public static AlarmManagerStatic getWithException() {
    return BlackReflection.create(AlarmManagerStatic.class, null, true);
  }

  public static AlarmManagerStatic get() {
    return BlackReflection.create(AlarmManagerStatic.class, null, false);
  }

  public static AlarmManagerContext getWithException(final Object caller) {
    return BlackReflection.create(AlarmManagerContext.class, caller, true);
  }

  public static AlarmManagerContext get(final Object caller) {
    return BlackReflection.create(AlarmManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(AlarmManagerContext.class);
  }
}

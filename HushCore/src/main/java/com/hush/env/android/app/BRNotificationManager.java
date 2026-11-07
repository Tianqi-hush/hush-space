package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRNotificationManager {
  public static NotificationManagerStatic getWithException() {
    return BlackReflection.create(NotificationManagerStatic.class, null, true);
  }

  public static NotificationManagerStatic get() {
    return BlackReflection.create(NotificationManagerStatic.class, null, false);
  }

  public static NotificationManagerContext getWithException(final Object caller) {
    return BlackReflection.create(NotificationManagerContext.class, caller, true);
  }

  public static NotificationManagerContext get(final Object caller) {
    return BlackReflection.create(NotificationManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(NotificationManagerContext.class);
  }
}

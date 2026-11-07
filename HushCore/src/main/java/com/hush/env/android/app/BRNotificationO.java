package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRNotificationO {
  public static NotificationOStatic getWithException() {
    return BlackReflection.create(NotificationOStatic.class, null, true);
  }

  public static NotificationOStatic get() {
    return BlackReflection.create(NotificationOStatic.class, null, false);
  }

  public static NotificationOContext getWithException(final Object caller) {
    return BlackReflection.create(NotificationOContext.class, caller, true);
  }

  public static NotificationOContext get(final Object caller) {
    return BlackReflection.create(NotificationOContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(NotificationOContext.class);
  }
}

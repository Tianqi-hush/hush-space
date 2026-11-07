package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRNotificationChannel {
  public static NotificationChannelStatic getWithException() {
    return BlackReflection.create(NotificationChannelStatic.class, null, true);
  }

  public static NotificationChannelStatic get() {
    return BlackReflection.create(NotificationChannelStatic.class, null, false);
  }

  public static NotificationChannelContext getWithException(final Object caller) {
    return BlackReflection.create(NotificationChannelContext.class, caller, true);
  }

  public static NotificationChannelContext get(final Object caller) {
    return BlackReflection.create(NotificationChannelContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(NotificationChannelContext.class);
  }
}

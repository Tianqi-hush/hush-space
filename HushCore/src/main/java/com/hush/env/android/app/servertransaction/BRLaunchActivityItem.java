package com.hush.env.android.app.servertransaction;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRLaunchActivityItem {
  public static LaunchActivityItemStatic getWithException() {
    return BlackReflection.create(LaunchActivityItemStatic.class, null, true);
  }

  public static LaunchActivityItemStatic get() {
    return BlackReflection.create(LaunchActivityItemStatic.class, null, false);
  }

  public static LaunchActivityItemContext getWithException(final Object caller) {
    return BlackReflection.create(LaunchActivityItemContext.class, caller, true);
  }

  public static LaunchActivityItemContext get(final Object caller) {
    return BlackReflection.create(LaunchActivityItemContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(LaunchActivityItemContext.class);
  }
}

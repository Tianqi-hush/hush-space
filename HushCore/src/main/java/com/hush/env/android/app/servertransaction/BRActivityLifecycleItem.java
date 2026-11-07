package com.hush.env.android.app.servertransaction;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRActivityLifecycleItem {
  public static ActivityLifecycleItemStatic getWithException() {
    return BlackReflection.create(ActivityLifecycleItemStatic.class, null, true);
  }

  public static ActivityLifecycleItemStatic get() {
    return BlackReflection.create(ActivityLifecycleItemStatic.class, null, false);
  }

  public static ActivityLifecycleItemContext getWithException(final Object caller) {
    return BlackReflection.create(ActivityLifecycleItemContext.class, caller, true);
  }

  public static ActivityLifecycleItemContext get(final Object caller) {
    return BlackReflection.create(ActivityLifecycleItemContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ActivityLifecycleItemContext.class);
  }
}

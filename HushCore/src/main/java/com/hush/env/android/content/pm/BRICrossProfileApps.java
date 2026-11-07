package com.hush.env.android.content.pm;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRICrossProfileApps {
  public static ICrossProfileAppsStatic getWithException() {
    return BlackReflection.create(ICrossProfileAppsStatic.class, null, true);
  }

  public static ICrossProfileAppsStatic get() {
    return BlackReflection.create(ICrossProfileAppsStatic.class, null, false);
  }

  public static ICrossProfileAppsContext getWithException(final Object caller) {
    return BlackReflection.create(ICrossProfileAppsContext.class, caller, true);
  }

  public static ICrossProfileAppsContext get(final Object caller) {
    return BlackReflection.create(ICrossProfileAppsContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ICrossProfileAppsContext.class);
  }
}

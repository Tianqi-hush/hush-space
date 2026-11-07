package com.hush.env.android.content.pm;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRLauncherApps {
  public static LauncherAppsStatic getWithException() {
    return BlackReflection.create(LauncherAppsStatic.class, null, true);
  }

  public static LauncherAppsStatic get() {
    return BlackReflection.create(LauncherAppsStatic.class, null, false);
  }

  public static LauncherAppsContext getWithException(final Object caller) {
    return BlackReflection.create(LauncherAppsContext.class, caller, true);
  }

  public static LauncherAppsContext get(final Object caller) {
    return BlackReflection.create(LauncherAppsContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(LauncherAppsContext.class);
  }
}

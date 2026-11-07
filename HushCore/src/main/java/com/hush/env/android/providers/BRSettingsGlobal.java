package com.hush.env.android.providers;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRSettingsGlobal {
  public static SettingsGlobalStatic getWithException() {
    return BlackReflection.create(SettingsGlobalStatic.class, null, true);
  }

  public static SettingsGlobalStatic get() {
    return BlackReflection.create(SettingsGlobalStatic.class, null, false);
  }

  public static SettingsGlobalContext getWithException(final Object caller) {
    return BlackReflection.create(SettingsGlobalContext.class, caller, true);
  }

  public static SettingsGlobalContext get(final Object caller) {
    return BlackReflection.create(SettingsGlobalContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(SettingsGlobalContext.class);
  }
}

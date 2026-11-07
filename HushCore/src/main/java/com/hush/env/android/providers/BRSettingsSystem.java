package com.hush.env.android.providers;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRSettingsSystem {
  public static SettingsSystemStatic getWithException() {
    return BlackReflection.create(SettingsSystemStatic.class, null, true);
  }

  public static SettingsSystemStatic get() {
    return BlackReflection.create(SettingsSystemStatic.class, null, false);
  }

  public static SettingsSystemContext getWithException(final Object caller) {
    return BlackReflection.create(SettingsSystemContext.class, caller, true);
  }

  public static SettingsSystemContext get(final Object caller) {
    return BlackReflection.create(SettingsSystemContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(SettingsSystemContext.class);
  }
}

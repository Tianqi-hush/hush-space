package com.hush.env.android.providers;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRSettingsSecure {
  public static SettingsSecureStatic getWithException() {
    return BlackReflection.create(SettingsSecureStatic.class, null, true);
  }

  public static SettingsSecureStatic get() {
    return BlackReflection.create(SettingsSecureStatic.class, null, false);
  }

  public static SettingsSecureContext getWithException(final Object caller) {
    return BlackReflection.create(SettingsSecureContext.class, caller, true);
  }

  public static SettingsSecureContext get(final Object caller) {
    return BlackReflection.create(SettingsSecureContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(SettingsSecureContext.class);
  }
}

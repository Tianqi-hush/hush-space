package com.hush.env.android.providers;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRSettingsContentProviderHolder {
  public static SettingsContentProviderHolderStatic getWithException() {
    return BlackReflection.create(SettingsContentProviderHolderStatic.class, null, true);
  }

  public static SettingsContentProviderHolderStatic get() {
    return BlackReflection.create(SettingsContentProviderHolderStatic.class, null, false);
  }

  public static SettingsContentProviderHolderContext getWithException(final Object caller) {
    return BlackReflection.create(SettingsContentProviderHolderContext.class, caller, true);
  }

  public static SettingsContentProviderHolderContext get(final Object caller) {
    return BlackReflection.create(SettingsContentProviderHolderContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(SettingsContentProviderHolderContext.class);
  }
}

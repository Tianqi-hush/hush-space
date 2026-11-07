package com.hush.env.android.location.provider;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRProviderProperties {
  public static ProviderPropertiesStatic getWithException() {
    return BlackReflection.create(ProviderPropertiesStatic.class, null, true);
  }

  public static ProviderPropertiesStatic get() {
    return BlackReflection.create(ProviderPropertiesStatic.class, null, false);
  }

  public static ProviderPropertiesContext getWithException(final Object caller) {
    return BlackReflection.create(ProviderPropertiesContext.class, caller, true);
  }

  public static ProviderPropertiesContext get(final Object caller) {
    return BlackReflection.create(ProviderPropertiesContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ProviderPropertiesContext.class);
  }
}

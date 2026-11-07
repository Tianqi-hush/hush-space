package com.hush.env.android.graphics;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRCompatibility {
  public static CompatibilityStatic getWithException() {
    return BlackReflection.create(CompatibilityStatic.class, null, true);
  }

  public static CompatibilityStatic get() {
    return BlackReflection.create(CompatibilityStatic.class, null, false);
  }

  public static CompatibilityContext getWithException(final Object caller) {
    return BlackReflection.create(CompatibilityContext.class, caller, true);
  }

  public static CompatibilityContext get(final Object caller) {
    return BlackReflection.create(CompatibilityContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(CompatibilityContext.class);
  }
}

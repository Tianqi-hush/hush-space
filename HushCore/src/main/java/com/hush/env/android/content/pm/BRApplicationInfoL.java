package com.hush.env.android.content.pm;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRApplicationInfoL {
  public static ApplicationInfoLStatic getWithException() {
    return BlackReflection.create(ApplicationInfoLStatic.class, null, true);
  }

  public static ApplicationInfoLStatic get() {
    return BlackReflection.create(ApplicationInfoLStatic.class, null, false);
  }

  public static ApplicationInfoLContext getWithException(final Object caller) {
    return BlackReflection.create(ApplicationInfoLContext.class, caller, true);
  }

  public static ApplicationInfoLContext get(final Object caller) {
    return BlackReflection.create(ApplicationInfoLContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ApplicationInfoLContext.class);
  }
}

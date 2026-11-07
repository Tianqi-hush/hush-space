package com.hush.env.android.os;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRStrictMode {
  public static StrictModeStatic getWithException() {
    return BlackReflection.create(StrictModeStatic.class, null, true);
  }

  public static StrictModeStatic get() {
    return BlackReflection.create(StrictModeStatic.class, null, false);
  }

  public static StrictModeContext getWithException(final Object caller) {
    return BlackReflection.create(StrictModeContext.class, caller, true);
  }

  public static StrictModeContext get(final Object caller) {
    return BlackReflection.create(StrictModeContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(StrictModeContext.class);
  }
}

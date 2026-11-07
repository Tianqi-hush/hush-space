package com.hush.env.android.view;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRDisplay {
  public static DisplayStatic getWithException() {
    return BlackReflection.create(DisplayStatic.class, null, true);
  }

  public static DisplayStatic get() {
    return BlackReflection.create(DisplayStatic.class, null, false);
  }

  public static DisplayContext getWithException(final Object caller) {
    return BlackReflection.create(DisplayContext.class, caller, true);
  }

  public static DisplayContext get(final Object caller) {
    return BlackReflection.create(DisplayContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(DisplayContext.class);
  }
}

package com.hush.env.com.android.internal;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRRid {
  public static RidStatic getWithException() {
    return BlackReflection.create(RidStatic.class, null, true);
  }

  public static RidStatic get() {
    return BlackReflection.create(RidStatic.class, null, false);
  }

  public static RidContext getWithException(final Object caller) {
    return BlackReflection.create(RidContext.class, caller, true);
  }

  public static RidContext get(final Object caller) {
    return BlackReflection.create(RidContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(RidContext.class);
  }
}

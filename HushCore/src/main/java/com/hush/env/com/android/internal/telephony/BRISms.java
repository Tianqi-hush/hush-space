package com.hush.env.com.android.internal.telephony;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRISms {
  public static ISmsStatic getWithException() {
    return BlackReflection.create(ISmsStatic.class, null, true);
  }

  public static ISmsStatic get() {
    return BlackReflection.create(ISmsStatic.class, null, false);
  }

  public static ISmsContext getWithException(final Object caller) {
    return BlackReflection.create(ISmsContext.class, caller, true);
  }

  public static ISmsContext get(final Object caller) {
    return BlackReflection.create(ISmsContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ISmsContext.class);
  }
}

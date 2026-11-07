package com.hush.env.com.android.internal.telephony;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIMms {
  public static IMmsStatic getWithException() {
    return BlackReflection.create(IMmsStatic.class, null, true);
  }

  public static IMmsStatic get() {
    return BlackReflection.create(IMmsStatic.class, null, false);
  }

  public static IMmsContext getWithException(final Object caller) {
    return BlackReflection.create(IMmsContext.class, caller, true);
  }

  public static IMmsContext get(final Object caller) {
    return BlackReflection.create(IMmsContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IMmsContext.class);
  }
}

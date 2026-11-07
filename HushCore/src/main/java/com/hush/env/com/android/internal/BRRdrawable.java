package com.hush.env.com.android.internal;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRRdrawable {
  public static RdrawableStatic getWithException() {
    return BlackReflection.create(RdrawableStatic.class, null, true);
  }

  public static RdrawableStatic get() {
    return BlackReflection.create(RdrawableStatic.class, null, false);
  }

  public static RdrawableContext getWithException(final Object caller) {
    return BlackReflection.create(RdrawableContext.class, caller, true);
  }

  public static RdrawableContext get(final Object caller) {
    return BlackReflection.create(RdrawableContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(RdrawableContext.class);
  }
}

package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRContextImplICS {
  public static ContextImplICSStatic getWithException() {
    return BlackReflection.create(ContextImplICSStatic.class, null, true);
  }

  public static ContextImplICSStatic get() {
    return BlackReflection.create(ContextImplICSStatic.class, null, false);
  }

  public static ContextImplICSContext getWithException(final Object caller) {
    return BlackReflection.create(ContextImplICSContext.class, caller, true);
  }

  public static ContextImplICSContext get(final Object caller) {
    return BlackReflection.create(ContextImplICSContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ContextImplICSContext.class);
  }
}

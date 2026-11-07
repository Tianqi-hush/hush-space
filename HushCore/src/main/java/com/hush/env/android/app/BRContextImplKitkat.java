package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRContextImplKitkat {
  public static ContextImplKitkatStatic getWithException() {
    return BlackReflection.create(ContextImplKitkatStatic.class, null, true);
  }

  public static ContextImplKitkatStatic get() {
    return BlackReflection.create(ContextImplKitkatStatic.class, null, false);
  }

  public static ContextImplKitkatContext getWithException(final Object caller) {
    return BlackReflection.create(ContextImplKitkatContext.class, caller, true);
  }

  public static ContextImplKitkatContext get(final Object caller) {
    return BlackReflection.create(ContextImplKitkatContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ContextImplKitkatContext.class);
  }
}

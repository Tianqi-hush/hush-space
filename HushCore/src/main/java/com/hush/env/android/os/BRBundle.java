package com.hush.env.android.os;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRBundle {
  public static BundleStatic getWithException() {
    return BlackReflection.create(BundleStatic.class, null, true);
  }

  public static BundleStatic get() {
    return BlackReflection.create(BundleStatic.class, null, false);
  }

  public static BundleContext getWithException(final Object caller) {
    return BlackReflection.create(BundleContext.class, caller, true);
  }

  public static BundleContext get(final Object caller) {
    return BlackReflection.create(BundleContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(BundleContext.class);
  }
}

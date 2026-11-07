package com.hush.env.com.android.internal;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRRlayout {
  public static RlayoutStatic getWithException() {
    return BlackReflection.create(RlayoutStatic.class, null, true);
  }

  public static RlayoutStatic get() {
    return BlackReflection.create(RlayoutStatic.class, null, false);
  }

  public static RlayoutContext getWithException(final Object caller) {
    return BlackReflection.create(RlayoutContext.class, caller, true);
  }

  public static RlayoutContext get(final Object caller) {
    return BlackReflection.create(RlayoutContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(RlayoutContext.class);
  }
}

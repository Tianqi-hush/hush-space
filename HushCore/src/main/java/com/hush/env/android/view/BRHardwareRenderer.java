package com.hush.env.android.view;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRHardwareRenderer {
  public static HardwareRendererStatic getWithException() {
    return BlackReflection.create(HardwareRendererStatic.class, null, true);
  }

  public static HardwareRendererStatic get() {
    return BlackReflection.create(HardwareRendererStatic.class, null, false);
  }

  public static HardwareRendererContext getWithException(final Object caller) {
    return BlackReflection.create(HardwareRendererContext.class, caller, true);
  }

  public static HardwareRendererContext get(final Object caller) {
    return BlackReflection.create(HardwareRendererContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(HardwareRendererContext.class);
  }
}

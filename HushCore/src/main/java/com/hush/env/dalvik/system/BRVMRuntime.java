package com.hush.env.dalvik.system;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRVMRuntime {
  public static VMRuntimeStatic getWithException() {
    return BlackReflection.create(VMRuntimeStatic.class, null, true);
  }

  public static VMRuntimeStatic get() {
    return BlackReflection.create(VMRuntimeStatic.class, null, false);
  }

  public static VMRuntimeContext getWithException(final Object caller) {
    return BlackReflection.create(VMRuntimeContext.class, caller, true);
  }

  public static VMRuntimeContext get(final Object caller) {
    return BlackReflection.create(VMRuntimeContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(VMRuntimeContext.class);
  }
}

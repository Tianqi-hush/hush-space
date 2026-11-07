package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRService {
  public static ServiceStatic getWithException() {
    return BlackReflection.create(ServiceStatic.class, null, true);
  }

  public static ServiceStatic get() {
    return BlackReflection.create(ServiceStatic.class, null, false);
  }

  public static ServiceContext getWithException(final Object caller) {
    return BlackReflection.create(ServiceContext.class, caller, true);
  }

  public static ServiceContext get(final Object caller) {
    return BlackReflection.create(ServiceContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ServiceContext.class);
  }
}

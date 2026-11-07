package com.hush.env.com.android.internal.os;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIVibratorService {
  public static IVibratorServiceStatic getWithException() {
    return BlackReflection.create(IVibratorServiceStatic.class, null, true);
  }

  public static IVibratorServiceStatic get() {
    return BlackReflection.create(IVibratorServiceStatic.class, null, false);
  }

  public static IVibratorServiceContext getWithException(final Object caller) {
    return BlackReflection.create(IVibratorServiceContext.class, caller, true);
  }

  public static IVibratorServiceContext get(final Object caller) {
    return BlackReflection.create(IVibratorServiceContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IVibratorServiceContext.class);
  }
}

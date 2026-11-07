package com.hush.env.com.android.internal.telecom;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRITelecomService {
  public static ITelecomServiceStatic getWithException() {
    return BlackReflection.create(ITelecomServiceStatic.class, null, true);
  }

  public static ITelecomServiceStatic get() {
    return BlackReflection.create(ITelecomServiceStatic.class, null, false);
  }

  public static ITelecomServiceContext getWithException(final Object caller) {
    return BlackReflection.create(ITelecomServiceContext.class, caller, true);
  }

  public static ITelecomServiceContext get(final Object caller) {
    return BlackReflection.create(ITelecomServiceContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ITelecomServiceContext.class);
  }
}

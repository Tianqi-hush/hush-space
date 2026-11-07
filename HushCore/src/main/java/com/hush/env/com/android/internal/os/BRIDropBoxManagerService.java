package com.hush.env.com.android.internal.os;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIDropBoxManagerService {
  public static IDropBoxManagerServiceStatic getWithException() {
    return BlackReflection.create(IDropBoxManagerServiceStatic.class, null, true);
  }

  public static IDropBoxManagerServiceStatic get() {
    return BlackReflection.create(IDropBoxManagerServiceStatic.class, null, false);
  }

  public static IDropBoxManagerServiceContext getWithException(final Object caller) {
    return BlackReflection.create(IDropBoxManagerServiceContext.class, caller, true);
  }

  public static IDropBoxManagerServiceContext get(final Object caller) {
    return BlackReflection.create(IDropBoxManagerServiceContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IDropBoxManagerServiceContext.class);
  }
}

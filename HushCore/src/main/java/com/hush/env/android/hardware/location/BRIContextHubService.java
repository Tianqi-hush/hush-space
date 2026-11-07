package com.hush.env.android.hardware.location;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIContextHubService {
  public static IContextHubServiceStatic getWithException() {
    return BlackReflection.create(IContextHubServiceStatic.class, null, true);
  }

  public static IContextHubServiceStatic get() {
    return BlackReflection.create(IContextHubServiceStatic.class, null, false);
  }

  public static IContextHubServiceContext getWithException(final Object caller) {
    return BlackReflection.create(IContextHubServiceContext.class, caller, true);
  }

  public static IContextHubServiceContext get(final Object caller) {
    return BlackReflection.create(IContextHubServiceContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IContextHubServiceContext.class);
  }
}

package com.hush.env.android.hardware.location;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIContextHubServiceStub {
  public static IContextHubServiceStubStatic getWithException() {
    return BlackReflection.create(IContextHubServiceStubStatic.class, null, true);
  }

  public static IContextHubServiceStubStatic get() {
    return BlackReflection.create(IContextHubServiceStubStatic.class, null, false);
  }

  public static IContextHubServiceStubContext getWithException(final Object caller) {
    return BlackReflection.create(IContextHubServiceStubContext.class, caller, true);
  }

  public static IContextHubServiceStubContext get(final Object caller) {
    return BlackReflection.create(IContextHubServiceStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IContextHubServiceStubContext.class);
  }
}

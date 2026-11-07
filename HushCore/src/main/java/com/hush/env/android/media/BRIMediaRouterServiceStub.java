package com.hush.env.android.media;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIMediaRouterServiceStub {
  public static IMediaRouterServiceStubStatic getWithException() {
    return BlackReflection.create(IMediaRouterServiceStubStatic.class, null, true);
  }

  public static IMediaRouterServiceStubStatic get() {
    return BlackReflection.create(IMediaRouterServiceStubStatic.class, null, false);
  }

  public static IMediaRouterServiceStubContext getWithException(final Object caller) {
    return BlackReflection.create(IMediaRouterServiceStubContext.class, caller, true);
  }

  public static IMediaRouterServiceStubContext get(final Object caller) {
    return BlackReflection.create(IMediaRouterServiceStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IMediaRouterServiceStubContext.class);
  }
}

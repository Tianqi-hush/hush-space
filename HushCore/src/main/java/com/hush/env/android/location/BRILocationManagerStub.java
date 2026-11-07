package com.hush.env.android.location;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRILocationManagerStub {
  public static ILocationManagerStubStatic getWithException() {
    return BlackReflection.create(ILocationManagerStubStatic.class, null, true);
  }

  public static ILocationManagerStubStatic get() {
    return BlackReflection.create(ILocationManagerStubStatic.class, null, false);
  }

  public static ILocationManagerStubContext getWithException(final Object caller) {
    return BlackReflection.create(ILocationManagerStubContext.class, caller, true);
  }

  public static ILocationManagerStubContext get(final Object caller) {
    return BlackReflection.create(ILocationManagerStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ILocationManagerStubContext.class);
  }
}

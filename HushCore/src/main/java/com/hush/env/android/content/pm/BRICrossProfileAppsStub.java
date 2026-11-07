package com.hush.env.android.content.pm;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRICrossProfileAppsStub {
  public static ICrossProfileAppsStubStatic getWithException() {
    return BlackReflection.create(ICrossProfileAppsStubStatic.class, null, true);
  }

  public static ICrossProfileAppsStubStatic get() {
    return BlackReflection.create(ICrossProfileAppsStubStatic.class, null, false);
  }

  public static ICrossProfileAppsStubContext getWithException(final Object caller) {
    return BlackReflection.create(ICrossProfileAppsStubContext.class, caller, true);
  }

  public static ICrossProfileAppsStubContext get(final Object caller) {
    return BlackReflection.create(ICrossProfileAppsStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ICrossProfileAppsStubContext.class);
  }
}

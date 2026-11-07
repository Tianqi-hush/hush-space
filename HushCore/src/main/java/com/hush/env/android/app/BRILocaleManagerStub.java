package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRILocaleManagerStub {
  public static ILocaleManagerStubStatic getWithException() {
    return BlackReflection.create(ILocaleManagerStubStatic.class, null, true);
  }

  public static ILocaleManagerStubStatic get() {
    return BlackReflection.create(ILocaleManagerStubStatic.class, null, false);
  }

  public static ILocaleManagerStubContext getWithException(final Object caller) {
    return BlackReflection.create(ILocaleManagerStubContext.class, caller, true);
  }

  public static ILocaleManagerStubContext get(final Object caller) {
    return BlackReflection.create(ILocaleManagerStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ILocaleManagerStubContext.class);
  }
}

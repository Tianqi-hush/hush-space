package com.hush.env.android.app;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRISearchManagerStub {
  public static ISearchManagerStubStatic getWithException() {
    return BlackReflection.create(ISearchManagerStubStatic.class, null, true);
  }

  public static ISearchManagerStubStatic get() {
    return BlackReflection.create(ISearchManagerStubStatic.class, null, false);
  }

  public static ISearchManagerStubContext getWithException(final Object caller) {
    return BlackReflection.create(ISearchManagerStubContext.class, caller, true);
  }

  public static ISearchManagerStubContext get(final Object caller) {
    return BlackReflection.create(ISearchManagerStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ISearchManagerStubContext.class);
  }
}

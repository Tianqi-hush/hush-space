package com.hush.env.android.content;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIRestrictionsManagerStub {
  public static IRestrictionsManagerStubStatic getWithException() {
    return BlackReflection.create(IRestrictionsManagerStubStatic.class, null, true);
  }

  public static IRestrictionsManagerStubStatic get() {
    return BlackReflection.create(IRestrictionsManagerStubStatic.class, null, false);
  }

  public static IRestrictionsManagerStubContext getWithException(final Object caller) {
    return BlackReflection.create(IRestrictionsManagerStubContext.class, caller, true);
  }

  public static IRestrictionsManagerStubContext get(final Object caller) {
    return BlackReflection.create(IRestrictionsManagerStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IRestrictionsManagerStubContext.class);
  }
}

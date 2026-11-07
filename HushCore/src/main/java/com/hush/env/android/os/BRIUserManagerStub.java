package com.hush.env.android.os;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIUserManagerStub {
  public static IUserManagerStubStatic getWithException() {
    return BlackReflection.create(IUserManagerStubStatic.class, null, true);
  }

  public static IUserManagerStubStatic get() {
    return BlackReflection.create(IUserManagerStubStatic.class, null, false);
  }

  public static IUserManagerStubContext getWithException(final Object caller) {
    return BlackReflection.create(IUserManagerStubContext.class, caller, true);
  }

  public static IUserManagerStubContext get(final Object caller) {
    return BlackReflection.create(IUserManagerStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IUserManagerStubContext.class);
  }
}

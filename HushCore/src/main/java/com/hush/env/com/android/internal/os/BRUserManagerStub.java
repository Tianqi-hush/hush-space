package com.hush.env.com.android.internal.os;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRUserManagerStub {
  public static UserManagerStubStatic getWithException() {
    return BlackReflection.create(UserManagerStubStatic.class, null, true);
  }

  public static UserManagerStubStatic get() {
    return BlackReflection.create(UserManagerStubStatic.class, null, false);
  }

  public static UserManagerStubContext getWithException(final Object caller) {
    return BlackReflection.create(UserManagerStubContext.class, caller, true);
  }

  public static UserManagerStubContext get(final Object caller) {
    return BlackReflection.create(UserManagerStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(UserManagerStubContext.class);
  }
}

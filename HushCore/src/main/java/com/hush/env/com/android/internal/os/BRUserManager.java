package com.hush.env.com.android.internal.os;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRUserManager {
  public static UserManagerStatic getWithException() {
    return BlackReflection.create(UserManagerStatic.class, null, true);
  }

  public static UserManagerStatic get() {
    return BlackReflection.create(UserManagerStatic.class, null, false);
  }

  public static UserManagerContext getWithException(final Object caller) {
    return BlackReflection.create(UserManagerContext.class, caller, true);
  }

  public static UserManagerContext get(final Object caller) {
    return BlackReflection.create(UserManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(UserManagerContext.class);
  }
}

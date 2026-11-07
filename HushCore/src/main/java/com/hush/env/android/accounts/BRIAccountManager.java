package com.hush.env.android.accounts;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIAccountManager {
  public static IAccountManagerStatic getWithException() {
    return BlackReflection.create(IAccountManagerStatic.class, null, true);
  }

  public static IAccountManagerStatic get() {
    return BlackReflection.create(IAccountManagerStatic.class, null, false);
  }

  public static IAccountManagerContext getWithException(final Object caller) {
    return BlackReflection.create(IAccountManagerContext.class, caller, true);
  }

  public static IAccountManagerContext get(final Object caller) {
    return BlackReflection.create(IAccountManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IAccountManagerContext.class);
  }
}

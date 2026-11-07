package com.hush.env.android.content;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIRestrictionsManager {
  public static IRestrictionsManagerStatic getWithException() {
    return BlackReflection.create(IRestrictionsManagerStatic.class, null, true);
  }

  public static IRestrictionsManagerStatic get() {
    return BlackReflection.create(IRestrictionsManagerStatic.class, null, false);
  }

  public static IRestrictionsManagerContext getWithException(final Object caller) {
    return BlackReflection.create(IRestrictionsManagerContext.class, caller, true);
  }

  public static IRestrictionsManagerContext get(final Object caller) {
    return BlackReflection.create(IRestrictionsManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IRestrictionsManagerContext.class);
  }
}

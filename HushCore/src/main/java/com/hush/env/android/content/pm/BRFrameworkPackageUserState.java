package com.hush.env.android.content.pm;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRFrameworkPackageUserState {
  public static FrameworkPackageUserStateStatic getWithException() {
    return BlackReflection.create(FrameworkPackageUserStateStatic.class, null, true);
  }

  public static FrameworkPackageUserStateStatic get() {
    return BlackReflection.create(FrameworkPackageUserStateStatic.class, null, false);
  }

  public static FrameworkPackageUserStateContext getWithException(final Object caller) {
    return BlackReflection.create(FrameworkPackageUserStateContext.class, caller, true);
  }

  public static FrameworkPackageUserStateContext get(final Object caller) {
    return BlackReflection.create(FrameworkPackageUserStateContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(FrameworkPackageUserStateContext.class);
  }
}

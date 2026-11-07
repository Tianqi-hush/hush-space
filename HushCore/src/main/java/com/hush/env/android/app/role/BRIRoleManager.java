package com.hush.env.android.app.role;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIRoleManager {
  public static IRoleManagerStatic getWithException() {
    return BlackReflection.create(IRoleManagerStatic.class, null, true);
  }

  public static IRoleManagerStatic get() {
    return BlackReflection.create(IRoleManagerStatic.class, null, false);
  }

  public static IRoleManagerContext getWithException(final Object caller) {
    return BlackReflection.create(IRoleManagerContext.class, caller, true);
  }

  public static IRoleManagerContext get(final Object caller) {
    return BlackReflection.create(IRoleManagerContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IRoleManagerContext.class);
  }
}

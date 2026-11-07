package com.hush.env.android.permission;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIPermissionManagerStub {
  public static IPermissionManagerStubStatic getWithException() {
    return BlackReflection.create(IPermissionManagerStubStatic.class, null, true);
  }

  public static IPermissionManagerStubStatic get() {
    return BlackReflection.create(IPermissionManagerStubStatic.class, null, false);
  }

  public static IPermissionManagerStubContext getWithException(final Object caller) {
    return BlackReflection.create(IPermissionManagerStubContext.class, caller, true);
  }

  public static IPermissionManagerStubContext get(final Object caller) {
    return BlackReflection.create(IPermissionManagerStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IPermissionManagerStubContext.class);
  }
}

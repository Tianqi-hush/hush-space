package com.hush.env.model.vivo;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIVivoPermissionServiceStub {
  public static IVivoPermissionServiceStubStatic getWithException() {
    return BlackReflection.create(IVivoPermissionServiceStubStatic.class, null, true);
  }

  public static IVivoPermissionServiceStubStatic get() {
    return BlackReflection.create(IVivoPermissionServiceStubStatic.class, null, false);
  }

  public static IVivoPermissionServiceStubContext getWithException(final Object caller) {
    return BlackReflection.create(IVivoPermissionServiceStubContext.class, caller, true);
  }

  public static IVivoPermissionServiceStubContext get(final Object caller) {
    return BlackReflection.create(IVivoPermissionServiceStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IVivoPermissionServiceStubContext.class);
  }
}

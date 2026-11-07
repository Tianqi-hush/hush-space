package com.hush.env.model.vivo;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIVivoPermissionService {
  public static IVivoPermissionServiceStatic getWithException() {
    return BlackReflection.create(IVivoPermissionServiceStatic.class, null, true);
  }

  public static IVivoPermissionServiceStatic get() {
    return BlackReflection.create(IVivoPermissionServiceStatic.class, null, false);
  }

  public static IVivoPermissionServiceContext getWithException(final Object caller) {
    return BlackReflection.create(IVivoPermissionServiceContext.class, caller, true);
  }

  public static IVivoPermissionServiceContext get(final Object caller) {
    return BlackReflection.create(IVivoPermissionServiceContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IVivoPermissionServiceContext.class);
  }
}

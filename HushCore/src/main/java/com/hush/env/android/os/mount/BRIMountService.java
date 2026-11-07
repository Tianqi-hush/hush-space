package com.hush.env.android.os.mount;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIMountService {
  public static IMountServiceStatic getWithException() {
    return BlackReflection.create(IMountServiceStatic.class, null, true);
  }

  public static IMountServiceStatic get() {
    return BlackReflection.create(IMountServiceStatic.class, null, false);
  }

  public static IMountServiceContext getWithException(final Object caller) {
    return BlackReflection.create(IMountServiceContext.class, caller, true);
  }

  public static IMountServiceContext get(final Object caller) {
    return BlackReflection.create(IMountServiceContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IMountServiceContext.class);
  }
}

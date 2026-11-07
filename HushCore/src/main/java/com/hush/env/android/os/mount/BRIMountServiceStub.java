package com.hush.env.android.os.mount;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIMountServiceStub {
  public static IMountServiceStubStatic getWithException() {
    return BlackReflection.create(IMountServiceStubStatic.class, null, true);
  }

  public static IMountServiceStubStatic get() {
    return BlackReflection.create(IMountServiceStubStatic.class, null, false);
  }

  public static IMountServiceStubContext getWithException(final Object caller) {
    return BlackReflection.create(IMountServiceStubContext.class, caller, true);
  }

  public static IMountServiceStubContext get(final Object caller) {
    return BlackReflection.create(IMountServiceStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IMountServiceStubContext.class);
  }
}

package com.hush.env.android.os;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRIPowerManagerStub {
  public static IPowerManagerStubStatic getWithException() {
    return BlackReflection.create(IPowerManagerStubStatic.class, null, true);
  }

  public static IPowerManagerStubStatic get() {
    return BlackReflection.create(IPowerManagerStubStatic.class, null, false);
  }

  public static IPowerManagerStubContext getWithException(final Object caller) {
    return BlackReflection.create(IPowerManagerStubContext.class, caller, true);
  }

  public static IPowerManagerStubContext get(final Object caller) {
    return BlackReflection.create(IPowerManagerStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(IPowerManagerStubContext.class);
  }
}

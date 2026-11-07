package com.hush.env.com.android.internal.telephony;

import java.lang.Class;
import java.lang.Object;
import com.hush.reflection.BlackReflection;

public class BRISmsStub {
  public static ISmsStubStatic getWithException() {
    return BlackReflection.create(ISmsStubStatic.class, null, true);
  }

  public static ISmsStubStatic get() {
    return BlackReflection.create(ISmsStubStatic.class, null, false);
  }

  public static ISmsStubContext getWithException(final Object caller) {
    return BlackReflection.create(ISmsStubContext.class, caller, true);
  }

  public static ISmsStubContext get(final Object caller) {
    return BlackReflection.create(ISmsStubContext.class, caller, false);
  }

  public static Class getRealClass() {
    return com.hush.reflection.utils.ClassUtil.classReady(ISmsStubContext.class);
  }
}
